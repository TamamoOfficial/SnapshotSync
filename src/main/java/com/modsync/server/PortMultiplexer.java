package com.modsync.server;

import com.modsync.ModSync;
import com.modsync.ModSyncConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Shares one TCP port between the Minecraft server and the HTTP download service.
 *
 * <p>This is what makes a single forwarded port work: an FRP/NAT tunnel that only publishes the
 * game port can carry both, because the first bytes of a connection say which protocol it is.
 * Minecraft opens with a VarInt packet length followed by packet id 0x00, while HTTP starts with
 * a request method in ASCII.
 *
 * <p>Layout: {@code public port -> this multiplexer -> game port} for game traffic, and HTTP is
 * answered in-process.
 */
public final class PortMultiplexer {

    /** How many leading bytes to sniff before deciding the protocol. */
    private static final int SNIFF_BYTES = 9;

    /** Length of the {@code MODSYNC1} magic, used to decide when sniffing can stop. */
    private static final int MAGIC_LEN = com.modsync.net.ModSyncProtocol.MAGIC.length();

    private final int listenPort;
    private final int gamePort;
    private final Path jarDir;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

    public PortMultiplexer(int listenPort, int gamePort, Path jarDir) {
        this.listenPort = listenPort;
        this.gamePort = gamePort;
        this.jarDir = jarDir;
    }

    public boolean start() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(listenPort), 128);
            running = true;

            acceptThread = new Thread(this::acceptLoop, "ModSync-Multiplexer");
            acceptThread.setDaemon(true);
            acceptThread.start();

            ModSync.LOGGER.info(
                    "ModSync port multiplexer on {} (game -> 127.0.0.1:{}, HTTP served in-process)",
                    listenPort, gamePort);
            return true;
        } catch (IOException e) {
            ModSync.LOGGER.error("Could not bind multiplexer port {}", listenPort, e);
            return false;
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                Thread t = new Thread(() -> handle(client), "ModSync-Conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) {
                    ModSync.LOGGER.warn("Multiplexer accept failed", e);
                }
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(5000);
            InputStream in = client.getInputStream();

            // Sniff enough bytes to classify the connection.
            //
            //   MODSYNC1 ...  -> our own mod-download protocol (8-byte magic)
            //   xx 00 ...     -> Minecraft handshake (VarInt length, then packet id 0x00)
            //
            // Nine bytes covers the full magic plus a trailing space. A Minecraft handshake is
            // detected as soon as the second byte is 0x00, so it never waits for the rest.
            byte[] head = new byte[SNIFF_BYTES];
            int read = 0;
            while (read < SNIFF_BYTES) {
                int n = in.read(head, read, SNIFF_BYTES - read);
                if (n < 0) {
                    break;
                }
                read += n;
                // A Minecraft login is decided by the first two bytes; do not stall on it.
                if (read >= 2 && head[0] != (byte) 'M') {
                    break;
                }
                if (read >= MAGIC_LEN && !com.modsync.net.ModSyncProtocol.startsWithMagic(head, read)) {
                    break;
                }
            }

            if (read <= 0) {
                ModSync.LOGGER.warn("ModSync: empty connection, dropping");
                client.close();
                return;
            }

            boolean modsync = com.modsync.net.ModSyncProtocol.startsWithMagic(head, read);
            ModSync.LOGGER.info("ModSync: connection classified as {} (first {} bytes: {})",
                    modsync ? "MODSYNC" : "GAME", read, preview(head, read));

            // Long-lived game connections must not inherit the sniff timeout.
            client.setSoTimeout(0);

            if (modsync) {
                handleModSync(client, head, read);
            } else {
                handleGame(client, head, read);
            }
        } catch (Exception e) {
            ModSync.LOGGER.warn("ModSync: connection handling failed", e);
        }
    }

    /** Short printable preview of the sniffed bytes, for diagnostics. */
    private static String preview(byte[] head, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            int b = head[i] & 0xFF;
            if (b >= 32 && b < 127) {
                sb.append((char) b);
            } else {
                sb.append(String.format("\\x%02x", b));
            }
        }
        return sb.toString();
    }

    /** True when the sniffed prefix is an HTTP request method. */
    private static boolean looksLikeHttp(byte[] head, int len) {
        String s = new String(head, 0, len, StandardCharsets.ISO_8859_1);
        return s.startsWith("GET ") || s.startsWith("HEAD") || s.startsWith("POST")
                || s.startsWith("PUT ") || s.startsWith("DELE") || s.startsWith("OPTI");
    }

    // ------------------------------------------------------------------ game

    private void handleGame(Socket client, byte[] head, int headLen) {
        try (Socket backend = new Socket()) {
            backend.connect(new InetSocketAddress("127.0.0.1", gamePort), 5000);
            backend.setTcpNoDelay(true);

            // Replay the bytes we consumed while sniffing.
            OutputStream backOut = backend.getOutputStream();
            backOut.write(head, 0, headLen);
            backOut.flush();

            Thread up = new Thread(() -> pump(client, backend), "ModSync-Up");
            Thread down = new Thread(() -> pump(backend, client), "ModSync-Down");
            up.setDaemon(true);
            down.setDaemon(true);
            up.start();
            down.start();
            up.join();
            down.join();
        } catch (Exception e) {
            ModSync.LOGGER.debug("Game relay failed", e);
        } finally {
            closeQuietly(client);
        }
    }

    private static void pump(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try {
                to.shutdownOutput();
            } catch (IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ http


    // --------------------------------------------------------------- modsync

    /**
     * Serves the jar catalogue and file contents over the custom protocol.
     *
     * <p>The client's request line has already been partially consumed by sniffing, so the
     * sniffed bytes are replayed through a prefixed stream.
     */
    private void handleModSync(Socket client, byte[] head, int headLen) throws IOException {
        InputStream in = new java.io.SequenceInputStream(
                new java.io.ByteArrayInputStream(head, 0, headLen),
                client.getInputStream());
        OutputStream out = client.getOutputStream();

        String line = com.modsync.net.ModSyncProtocol.readLine(in);
        if (line == null) {
            return;
        }
        line = line.trim();
        ModSync.LOGGER.info("ModSync: request '{}'", line);

        String magic = com.modsync.net.ModSyncProtocol.MAGIC;
        if (!line.startsWith(magic)) {
            return;
        }
        String rest = line.substring(magic.length()).trim();

        if (rest.equalsIgnoreCase(com.modsync.net.ModSyncProtocol.VERB_LIST)) {
            // int32 count, then per file: int32 nameLen, name, int64 size
            java.util.List<Path> jars = new java.util.ArrayList<>();
            try (var stream = Files.list(jarDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString()
                                .toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .sorted()
                        .forEach(jars::add);
            }
            com.modsync.net.ModSyncProtocol.writeInt(out, jars.size());
            for (Path jar : jars) {
                byte[] name = jar.getFileName().toString().getBytes(StandardCharsets.UTF_8);
                com.modsync.net.ModSyncProtocol.writeInt(out, name.length);
                out.write(name);
                com.modsync.net.ModSyncProtocol.writeLong(out, Files.size(jar));
            }
            out.flush();
            ModSync.LOGGER.info("ModSync: listed {} file(s)", jars.size());
            return;
        }

        if (rest.toUpperCase(Locale.ROOT).startsWith(com.modsync.net.ModSyncProtocol.VERB_GET)) {
            String name = rest.substring(com.modsync.net.ModSyncProtocol.VERB_GET.length()).trim();
            if (name.isEmpty() || name.contains("/") || name.contains("\\")
                    || name.contains("..") || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                com.modsync.net.ModSyncProtocol.writeInt(out, 1);
                out.flush();
                return;
            }
            Path file = jarDir.resolve(name).normalize();
            if (!file.startsWith(jarDir.normalize()) || !Files.isRegularFile(file)) {
                com.modsync.net.ModSyncProtocol.writeInt(out, 2);
                out.flush();
                return;
            }
            long size = Files.size(file);
            com.modsync.net.ModSyncProtocol.writeInt(out, 0);
            com.modsync.net.ModSyncProtocol.writeLong(out, size);
            try (var ch = Files.newInputStream(file)) {
                ch.transferTo(out);
            }
            out.flush();
            ModSync.LOGGER.info("ModSync: sent {} ({} bytes)", name, size);
            return;
        }

        ModSync.LOGGER.warn("ModSync: unknown request '{}'", line);
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
