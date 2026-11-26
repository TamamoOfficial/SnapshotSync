package com.modsync.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Remembers the server the player tried to join.
 *
 * <p>The authoritative source is {@link ServerData#ip} (the address the player typed).
 * A live socket peer is used only as a fallback, and the local client port is never recorded.
 */
public final class ServerAddressMemory {

    private static final Path FILE = Path.of("modsync", "last-server.txt");

    private static volatile String lastIp = "";

    private ServerAddressMemory() {
    }

    /**
     * Records the server from the client's own server entry. Called every tick while connected
     * so the value is stored before a mod-mismatch disconnect.
     */
    public static void capture() {
        try {
            ServerData current = Minecraft.getInstance().getCurrentServer();
            if (current != null && current.ip != null) {
                String ip = current.ip.trim();
                if (!ip.isEmpty() && !"0.0.0.0".equals(ip) && !"::".equals(ip)) {
                    persist(ip);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Explicitly remember a chosen server (multiplayer screen selection). */
    public static void remember(ServerData server) {
        if (server == null || server.ip == null || server.ip.isBlank()) {
            return;
        }
        String ip = server.ip.trim();
        if (!"0.0.0.0".equals(ip) && !"::".equals(ip)) {
            persist(ip);
        }
    }

    private static void persist(String ip) {
        if (ip.equals(lastIp)) {
            return;
        }
        lastIp = ip;
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, lastIp);
            com.modsync.ModSync.LOGGER.info("ModSync: remembered server address {}", lastIp);
        } catch (Exception ignored) {
        }
    }

    private static String raw() {
        if (!lastIp.isBlank()) {
            return lastIp;
        }
        try {
            if (Files.isRegularFile(FILE)) {
                String stored = Files.readString(FILE).trim();
                if (!stored.isEmpty()) {
                    lastIp = stored;
                    return stored;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** Host part (port and IPv6 brackets stripped). */
    public static String host() {
        String s = raw();
        if (s.isBlank()) {
            return "";
        }
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            return close > 0 ? s.substring(0, close + 1) : s;
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) {
            return s.substring(0, colon);
        }
        return s;
    }

    /** Port from the address, or the Minecraft default when absent. */
    public static int port() {
        String s = raw();
        if (s.isBlank()) {
            return 25565;
        }
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close > 0 && close + 1 < s.length() && s.charAt(close + 1) == ':') {
                return parse(s.substring(close + 2));
            }
            return 25565;
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) {
            return parse(s.substring(colon + 1));
        }
        return 25565;
    }

    private static int parse(String value) {
        try {
            int p = Integer.parseInt(value.trim());
            return p > 0 && p <= 65535 ? p : 25565;
        } catch (NumberFormatException e) {
            return 25565;
        }
    }
}
