package com.snapsync.client;

import com.snapsync.Config;
import com.snapsync.SnapshotSync;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Downloads one blob using several TCP connections, each fetching a distinct byte range.
 *
 * <p>Why ranges and not "one connection per file": a snapshot typically has a few very large
 * jars and many small files, so per-file parallelism would idle most connections. Splitting
 * <i>large</i> blobs into fixed-size chunks spreads the work evenly, and small blobs simply
 * get one chunk.
 *
 * <p>Each worker writes into its own offset of a pre-sized temp file, so no locking is needed
 * and no worker can see another's partial data.
 */
public final class ParallelDownloader {

    /** Below this size, splitting costs more round-trips than it saves. */
    private static final long MIN_CHUNK_BYTES = 128 * 1024;

    private final String baseUrl;
    private final int parallelism;
    private final long chunkSize;

    public ParallelDownloader(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.parallelism = Math.max(1, Config.maxParallelConnections);
        this.chunkSize = Math.max(MIN_CHUNK_BYTES, Config.chunkSizeKb * 1024L);
    }

    /**
     * Downloads {@code sha256} into {@code destination}.
     *
     * @param expectedSize size hint; when <= 0 it is discovered with a HEAD/GET probe
     */
    public void fetch(String sha256, long expectedSize, Path destination) throws Exception {
        String url = baseUrl + "/" + blobPathOf(sha256);

        long size = expectedSize > 0 ? expectedSize : probeSize(url);
        if (size <= 0) {
            throw new IOException("Could not determine size of blob " + sha256);
        }

        Files.createDirectories(destination.getParent());
        Path tmp = destination.resolveSibling(destination.getFileName() + ".part");

        // Pre-size the file so every worker can seek to its own offset.
        try (RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw")) {
            raf.setLength(size);
        }

        List<long[]> ranges = planRanges(size);

        if (ranges.size() == 1 || parallelism == 1) {
            // Single range: no need to pay for a thread.
            downloadRange(url, tmp, ranges.get(0)[0], ranges.get(0)[1], null);
        } else {
            runParallel(url, tmp, ranges, size);
        }

        // Content-address verify before we let the file loose on the filesystem.
        String actual = com.snapsync.core.Hashing.sha256Hex(tmp);
        if (!actual.equalsIgnoreCase(sha256)) {
            Files.deleteIfExists(tmp);
            throw new IOException("Hash mismatch for " + sha256 + " (got " + actual + ")");
        }

        Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String blobPathOf(String sha256) {
        // Same helper the builder uses, so client and server cannot drift apart.
        return "blobs/" + com.snapsync.core.SnapshotBuilder.blobRelative(sha256);
    }

    /** Splits [0,size) into chunks, capped so we never queue thousands of tiny requests. */
    private List<long[]> planRanges(long size) {
        if (size <= chunkSize) {
            return List.of(new long[]{0, size - 1});
        }

        List<long[]> ranges = new ArrayList<>();
        long start = 0;
        while (start < size) {
            long end = Math.min(start + chunkSize - 1, size - 1);
            ranges.add(new long[]{start, end});
            start = end + 1;
        }
        return ranges;
    }

    private void runParallel(String url, Path tmp, List<long[]> ranges, long totalSize) throws Exception {
        int threads = Math.min(parallelism, ranges.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "SnapshotSync-Download");
            t.setDaemon(true);
            return t;
        });

        AtomicLong done = new AtomicLong();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (long[] range : ranges) {
                futures.add(pool.submit(() -> {
                    downloadRange(url, tmp, range[0], range[1], () -> done.addAndGet(1));
                    return null;
                }));
            }

            // Surface the first failure instead of leaving a half-written file.
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private interface ChunkDone {
        void run();
    }

    private void downloadRange(String url, Path tmp, long start, long end, ChunkDone onDone)
            throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(30000);
            connection.setRequestMethod("GET");

            int code = connection.getResponseCode();

            if (code == HttpURLConnection.HTTP_OK) {
                // Server ignored Range: for a single-chunk request that is still correct,
                // but for a multi-chunk request it would corrupt the file.
                if (start != 0) {
                    throw new IOException("Server ignored Range request; cannot parallelise");
                }
            } else if (code != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("HTTP " + code + " for range " + start + "-" + end);
            }

            try (InputStream in = connection.getInputStream();
                 RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw")) {
                raf.seek(start);

                byte[] buffer = new byte[64 * 1024];
                long remaining = end - start + 1;

                while (remaining > 0) {
                    int want = (int) Math.min(buffer.length, remaining);
                    int n = in.read(buffer, 0, want);
                    if (n < 0) {
                        break;
                    }
                    raf.write(buffer, 0, n);
                    remaining -= n;
                }

                if (remaining > 0) {
                    throw new IOException("Short read: " + remaining + " bytes missing");
                }
            }

            if (onDone != null) {
                onDone.run();
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Determines the blob size.
     *
     * <p>HEAD is tried first, but not all servers send {@code Content-Length} on HEAD (the JDK's
     * own HttpServer did not, which made ranged downloads fail). The fallback asks for the first
     * byte and reads the total from {@code Content-Range}.
     */
    private long probeSize(String url) throws IOException {
        HttpURLConnection head = null;
        try {
            head = (HttpURLConnection) URI.create(url).toURL().openConnection();
            head.setRequestMethod("HEAD");
            head.setConnectTimeout(8000);
            head.setReadTimeout(15000);
            head.connect();

            if (head.getResponseCode() == HttpURLConnection.HTTP_OK) {
                long length = head.getContentLengthLong();
                if (length > 0) {
                    return length;
                }
            }
        } catch (IOException e) {
            SnapshotSync.LOGGER.debug("HEAD probe failed for {}, falling back", url, e);
        } finally {
            if (head != null) {
                head.disconnect();
            }
        }

        // Fallback: a one-byte ranged GET exposes the full size in Content-Range.
        HttpURLConnection ranged = null;
        try {
            ranged = (HttpURLConnection) URI.create(url).toURL().openConnection();
            ranged.setRequestProperty("Range", "bytes=0-0");
            ranged.setConnectTimeout(8000);
            ranged.setReadTimeout(15000);
            ranged.connect();

            int code = ranged.getResponseCode();
            if (code == HttpURLConnection.HTTP_PARTIAL || code == HttpURLConnection.HTTP_OK) {
                String contentRange = ranged.getHeaderField("Content-Range");
                if (contentRange != null) {
                    int slash = contentRange.lastIndexOf('/');
                    if (slash > 0) {
                        try {
                            return Long.parseLong(contentRange.substring(slash + 1).trim());
                        } catch (NumberFormatException ignored) {
                            // fall through
                        }
                    }
                }
                long length = ranged.getContentLengthLong();
                if (code == HttpURLConnection.HTTP_OK && length > 0) {
                    return length;
                }
            }
            return -1;
        } finally {
            if (ranged != null) {
                ranged.disconnect();
            }
        }
    }
}
