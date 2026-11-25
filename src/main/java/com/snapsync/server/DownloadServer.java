package com.snapsync.server;

import com.snapsync.SnapshotSync;
import com.snapsync.core.Manifest;
import com.snapsync.core.ManifestJson;
import com.snapsync.core.SnapshotBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves the published snapshot over HTTP: the manifest, and the content-addressed blobs.
 *
 * <p><b>Range support is mandatory, not a nicety</b>: the client's parallel downloader splits
 * a blob into byte ranges and fetches them on several connections at once. Without
 * {@code Accept-Ranges}/{@code 206} that design silently degrades to a single connection.
 *
 * <p>Blobs are immutable and named by their own SHA-256, which makes them safe to cache
 * forever; the manifest is the only mutable resource and is marked as such.
 */
public final class DownloadServer {

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private static DownloadServer instance;

    private final HttpServer server;
    private final int port;

    private DownloadServer(HttpServer server, int port) {
        this.server = server;
        this.port = port;
    }

    public int port() {
        return port;
    }

    public static synchronized DownloadServer instance() {
        return instance;
    }

    /** Binds and starts. Returns null (and logs) when the port is unusable. */
    public static synchronized DownloadServer start(int port) {
        if (instance != null) {
            if (instance.port == port) {
                return instance;
            }
            instance.stop();
        }

        try {
            Path repo = SnapshotBuilder.repoDir();
            Files.createDirectories(repo);
            Path repoAbs = repo.toAbsolutePath().normalize();

            HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
            http.createContext("/", exchange -> handle(exchange, repoAbs));
            http.setExecutor(Executors.newCachedThreadPool());
            http.start();

            instance = new DownloadServer(http, port);
            SnapshotSync.LOGGER.info("Snapshot download server listening on port {} (serving {})",
                    port, repoAbs);
            return instance;
        } catch (IOException e) {
            SnapshotSync.LOGGER.error("Could not bind download port {}; clients will fall back to "
                    + "the in-band manifest path", port, e);
            return null;
        }
    }

    public static synchronized void stopInstance() {
        if (instance != null) {
            instance.stop();
            instance = null;
        }
    }

    private void stop() {
        server.stop(0);
        SnapshotSync.LOGGER.info("Snapshot download server on port {} stopped", port);
    }

    // ------------------------------------------------------------------ routing

    private static void handle(HttpExchange exchange, Path repoAbs) throws IOException {
        String path = exchange.getRequestURI().getPath();

        try {
            String rel = path.startsWith("/") ? path.substring(1) : path;

            if (rel.equals("manifest.json") || rel.equals("current.json")) {
                serveManifest(exchange);
                return;
            }

            if (rel.startsWith("blobs/")) {
                Path file = repoAbs.resolve(rel).normalize();
                if (!file.startsWith(repoAbs)) {
                    send(exchange, 403);
                    return;
                }
                serveBlob(exchange, file);
                return;
            }

            if (rel.isEmpty() || rel.equals("health")) {
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }

            send(exchange, 404);
        } catch (Exception e) {
            SnapshotSync.LOGGER.error("Error handling {}", path, e);
            try {
                send(exchange, 500);
            } catch (IOException ignored) {
                // headers may already be committed
            }
        } finally {
            exchange.close();
        }
    }

    private static void serveManifest(HttpExchange exchange) throws IOException {
        Manifest manifest = SnapshotBuilder.current();
        if (manifest == null) {
            send(exchange, 404);
            return;
        }

        byte[] body = ManifestJson.toBytes(manifest);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.getResponseHeaders().add("ETag", "\"" + manifest.rootHash() + "\"");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void serveBlob(HttpExchange exchange, Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            send(exchange, 404);
            return;
        }

        long size = Files.size(file);

        // Immutable by construction: the name IS the content hash.
        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
        exchange.getResponseHeaders().add("Content-Length", Long.toString(size));
        exchange.getResponseHeaders().add("Cache-Control", "public, max-age=31536000, immutable");

        // A HEAD probe asks only for the headers. jdk.httpserver does not fill in
        // Content-Length for us on that path, and the client uses HEAD to size its ranged
        // downloads, so answer it explicitly and stop here.
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }

        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range == null || range.isBlank()) {
            exchange.sendResponseHeaders(200, size);
            try (OutputStream out = exchange.getResponseBody()) {
                Files.copy(file, out);
            }
            return;
        }

        Matcher m = RANGE.matcher(range.trim());
        if (!m.matches()) {
            exchange.getResponseHeaders().add("Content-Range", "bytes */" + size);
            send(exchange, 416);
            return;
        }

        long start;
        long end;
        try {
            String from = m.group(1);
            String to = m.group(2);

            if (from.isEmpty()) {
                // Suffix form: "bytes=-N" means the last N bytes.
                long suffix = Long.parseLong(to);
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(from);
                end = to.isEmpty() ? size - 1 : Long.parseLong(to);
            }
        } catch (NumberFormatException e) {
            exchange.getResponseHeaders().add("Content-Range", "bytes */" + size);
            send(exchange, 416);
            return;
        }

        if (start > end || start >= size) {
            exchange.getResponseHeaders().add("Content-Range", "bytes */" + size);
            send(exchange, 416);
            return;
        }
        end = Math.min(end, size - 1);

        long length = end - start + 1;
        exchange.getResponseHeaders().add("Content-Range",
                "bytes " + start + "-" + end + "/" + size);
        exchange.sendResponseHeaders(206, length);

        try (OutputStream out = exchange.getResponseBody();
             var channel = Files.newByteChannel(file)) {
            channel.position(start);
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int n = channel.read(java.nio.ByteBuffer.wrap(buffer, 0, want));
                if (n < 0) {
                    break;
                }
                out.write(buffer, 0, n);
                remaining -= n;
            }
        }
    }

    private static void send(HttpExchange exchange, int code) throws IOException {
        exchange.sendResponseHeaders(code, -1);
    }

    static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".json") ? "application/json" : "application/octet-stream";
    }
}
