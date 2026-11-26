package com.modsync.client;

import com.modsync.ModSync;
import com.modsync.net.ModSyncProtocol;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Fetches the server's jar list and downloads the jars over a plain-TCP custom protocol.
 *
 * <p>HTTP is deliberately avoided: tunnel providers such as SakuraFrp forward only raw TCP and
 * answer any HTTP-looking request themselves, so a normal web request never reaches the server.
 * See {@link ModSyncProtocol} for the wire format.
 */
public final class PackDownloader {

    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 30000;

    private PackDownloader() {
    }

    /** One published jar: its name and byte size. */
    public record RemoteFile(String name, long size) {
    }

    public record Result(boolean ok, List<RemoteFile> files, String error) {
    }

    /**
     * Download progress snapshot.
     *
     * @param total      jars to fetch
     * @param done       jars already finished (installed or skipped)
     * @param bytesTotal bytes expected across all jars, when known
     * @param bytesDone  bytes received so far
     * @param current    file name being transferred right now
     */
    public record Progress(int total, int done, long bytesTotal, long bytesDone, String current) {
        public float fraction() {
            if (bytesTotal > 0) {
                return Math.min(1f, (float) ((double) bytesDone / (double) bytesTotal));
            }
            return total > 0 ? Math.min(1f, (float) done / (float) total) : 0f;
        }
    }

    /** Result of a full download run. */
    public record Outcome(int installed, int skipped, int failed, boolean cancelled) {
    }

    /** Fallback port when the server advertised nothing: game port + 1. */
    public static int downloadPort(int gamePort) {
        String override = System.getProperty("modsync.clientPort");
        if (override != null) {
            try {
                int p = Integer.parseInt(override.trim());
                if (p > 0 && p <= 65535) {
                    return p;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        int derived = gamePort + 1;
        return derived > 65535 ? gamePort - 1 : derived;
    }

    private static boolean isSafeName(String name) {
        return name != null && !name.isEmpty()
                && !name.contains("/") && !name.contains("\\") && !name.contains("..")
                && name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar");
    }

    private static Socket connect(String host, int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
        socket.setSoTimeout(READ_TIMEOUT);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private static void sendLine(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Fetch the catalogue of published jars. */
    public static Result list(String host, int port) {
        try (Socket socket = connect(host, port)) {
            sendLine(socket.getOutputStream(), ModSyncProtocol.MAGIC + " " + ModSyncProtocol.VERB_LIST);

            InputStream in = socket.getInputStream();
            int count = ModSyncProtocol.readInt(in);
            if (count < 0 || count > 10000) {
                return new Result(false, List.of(), "bad count " + count);
            }
            List<RemoteFile> files = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int nameLen = ModSyncProtocol.readInt(in);
                if (nameLen < 0 || nameLen > 1024) {
                    return new Result(false, List.of(), "bad name length");
                }
                byte[] nameBytes = new byte[nameLen];
                in.readNBytes(nameBytes, 0, nameLen);
                long size = ModSyncProtocol.readLong(in);
                String name = new String(nameBytes, StandardCharsets.UTF_8);
                if (isSafeName(name)) {
                    files.add(new RemoteFile(name, size));
                }
            }
            return new Result(true, files, null);
        } catch (Exception e) {
            ModSync.LOGGER.warn("Could not fetch jar list from {}:{}", host, port, e);
            return new Result(false, List.of(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Download a single jar to {@code dest}, reporting byte progress. */
    private static void fetchFile(String host, int port, String name, Path dest,
                                  Consumer<Long> onBytes, BooleanSupplier cancelled) throws IOException {
        try (Socket socket = connect(host, port)) {
            sendLine(socket.getOutputStream(),
                    ModSyncProtocol.MAGIC + " " + ModSyncProtocol.VERB_GET + " " + name);

            InputStream in = socket.getInputStream();
            int status = ModSyncProtocol.readInt(in);
            if (status != 0) {
                throw new IOException("server returned status " + status + " for " + name);
            }
            long size = ModSyncProtocol.readLong(in);

            Path tmp = dest.resolveSibling(dest.getFileName() + ".modsync.part");
            byte[] buf = new byte[64 * 1024];
            long remaining = size;
            try (OutputStream out = Files.newOutputStream(tmp)) {
                while (remaining > 0) {
                    if (cancelled.getAsBoolean()) {
                        out.close();
                        Files.deleteIfExists(tmp);
                        throw new IOException("cancelled");
                    }
                    int want = (int) Math.min(buf.length, remaining);
                    int n = in.read(buf, 0, want);
                    if (n < 0) {
                        throw new IOException("unexpected end of file");
                    }
                    out.write(buf, 0, n);
                    remaining -= n;
                    onBytes.accept((long) n);
                }
            }
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Downloads every listed jar into {@code mods/}, reporting progress as it goes.
     */
    public static Outcome downloadAll(String host, int port, List<RemoteFile> files,
                                      Consumer<Progress> onProgress, BooleanSupplier cancelled)
            throws IOException {
        Path mods = Path.of("mods");
        Files.createDirectories(mods);

        // Measure: skip what we already have at the same size.
        long bytesTotal = 0;
        int done = 0;
        List<RemoteFile> todo = new ArrayList<>();
        for (RemoteFile f : files) {
            if (cancelled.getAsBoolean()) {
                return new Outcome(0, 0, 0, true);
            }
            Path target = mods.resolve(f.name()).normalize();
            boolean have = Files.isRegularFile(target) && Files.size(target) == f.size();
            if (have) {
                done++;
            } else {
                todo.add(f);
                bytesTotal += f.size();
            }
            onProgress.accept(new Progress(files.size(), done, bytesTotal, 0, f.name()));
        }

        final long bytesTotalFinal = bytesTotal;
        int installed = 0;
        int skipped = done;
        int failed = 0;
        final long[] bytesDone = {0L};
        final int[] doneRef = {done};
        final int totalFiles = files.size();

        for (RemoteFile f : todo) {
            if (cancelled.getAsBoolean()) {
                ModSync.LOGGER.info("ModSync: download cancelled after {} file(s)", installed);
                return new Outcome(installed, skipped, failed, true);
            }
            Path target = mods.resolve(f.name()).normalize();
            if (!target.startsWith(mods.normalize())) {
                continue;
            }
            try {
                fetchFile(host, port, f.name(), target,
                        delta -> {
                            bytesDone[0] += delta;
                            onProgress.accept(new Progress(totalFiles, doneRef[0],
                                    bytesTotalFinal, bytesDone[0], f.name()));
                        },
                        cancelled);
                installed++;
                done++;
                doneRef[0] = done;
            } catch (Exception e) {
                if (cancelled.getAsBoolean()) {
                    return new Outcome(installed, skipped, failed, true);
                }
                failed++;
                done++;
                doneRef[0] = done;
                ModSync.LOGGER.warn("Failed to download {}", f.name(), e);
            }
        }

        ModSync.LOGGER.info("ModSync: {} installed, {} skipped, {} failed", installed, skipped, failed);
        return new Outcome(installed, skipped, failed, false);
    }
}
