package com.modsync.server;

import com.modsync.ModSync;
import com.modsync.net.ModSyncProtocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Serves the jars in {@code config/modsync} over a raw TCP protocol on its own port.
 *
 * <p>HTTP is not used: tunnel providers such as SakuraFrp forward plain TCP and intercept
 * anything that looks like an HTTP request, so a web server would never be reachable. The
 * protocol is described in {@link ModSyncProtocol}.
 *
 * <p>This server binds its own port and never touches the Minecraft port, so a failure here
 * cannot stop the game server from starting.
 */
public final class DownloadServer {

    private static ServerSocket serverSocket;
    private static Thread acceptThread;
    private static volatile boolean running;

    private DownloadServer() {
    }

    public static void start() {
        int port = resolvePort();
        Path dir = Path.of(ModSync.MODS_DIR);
        try {
            Files.createDirectories(dir);
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new java.net.InetSocketAddress(port), 64);
            running = true;

            acceptThread = new Thread(DownloadServer::acceptLoop, "ModSync-Accept");
            acceptThread.setDaemon(true);
            acceptThread.start();

            ModSync.LOGGER.info("ModSync download server listening on {}, serving {}",
                    port, dir.toAbsolutePath());
        } catch (IOException e) {
            ModSync.LOGGER.error("Could not bind ModSync download port {}", port, e);
        }
    }

    public static void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
    }

    private static void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                Thread t = new Thread(() -> handle(client), "ModSync-Conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) {
                    ModSync.LOGGER.warn("ModSync accept failed", e);
                }
            }
        }
    }

    /** Handles one client: read the request line, then answer LIST or GET. */
    private static void handle(Socket client) {
        try (Socket socket = client) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(15000);

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            Path dir = Path.of(ModSync.MODS_DIR).toAbsolutePath().normalize();

            String line = readLine(in);
            if (line == null) {
                return;
            }
            line = line.trim();
            if (!line.startsWith(ModSyncProtocol.MAGIC)) {
                ModSync.LOGGER.warn("ModSync: rejected non-protocol request '{}'", line);
                return;
            }
            String rest = line.substring(ModSyncProtocol.MAGIC.length()).trim();

            if (rest.equalsIgnoreCase(ModSyncProtocol.VERB_LIST)) {
                List<Path> jars = listJars(dir);
                ModSyncProtocol.writeInt(out, jars.size());
                for (Path jar : jars) {
                    byte[] name = jar.getFileName().toString().getBytes(StandardCharsets.UTF_8);
                    ModSyncProtocol.writeInt(out, name.length);
                    out.write(name);
                    ModSyncProtocol.writeLong(out, Files.size(jar));
                }
                out.flush();
                ModSync.LOGGER.info("ModSync: listed {} file(s)", jars.size());
                return;
            }

            if (rest.toUpperCase(Locale.ROOT).startsWith(ModSyncProtocol.VERB_GET)) {
                String name = rest.substring(ModSyncProtocol.VERB_GET.length()).trim();
                if (name.isEmpty() || name.contains("/") || name.contains("\\")
                        || name.contains("..") || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    ModSyncProtocol.writeInt(out, 1);
                    out.flush();
                    return;
                }
                Path file = dir.resolve(name).normalize();
                if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
                    ModSyncProtocol.writeInt(out, 2);
                    out.flush();
                    return;
                }
                long size = Files.size(file);
                ModSyncProtocol.writeInt(out, 0);
                ModSyncProtocol.writeLong(out, size);
                try (InputStream fileIn = Files.newInputStream(file)) {
                    fileIn.transferTo(out);
                }
                out.flush();
                ModSync.LOGGER.info("ModSync: sent {} ({} bytes)", name, size);
                return;
            }

            ModSync.LOGGER.warn("ModSync: unknown request '{}'", line);
        } catch (Exception e) {
            ModSync.LOGGER.warn("ModSync: connection failed", e);
        }
    }

    /** Reads one LF-terminated ASCII/UTF-8 line. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return sb.toString();
            }
            if (b != '\r') {
                sb.append((char) b);
            }
            if (sb.length() > 512) {
                throw new IOException("request line too long");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static List<Path> listJars(Path dir) throws IOException {
        List<Path> jars = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .forEach(jars::add);
        }
        return jars;
    }

    private static int resolvePort() {
        String override = System.getProperty("modsync.port");
        if (override != null) {
            try {
                int p = Integer.parseInt(override.trim());
                if (p > 0 && p <= 65535) {
                    return p;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return com.modsync.ModSyncConfig.serverPort(25565);
    }
}
