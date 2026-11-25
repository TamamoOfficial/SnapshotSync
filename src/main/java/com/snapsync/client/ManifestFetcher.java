package com.snapsync.client;

import com.snapsync.Config;
import com.snapsync.SnapshotSync;
import com.snapsync.core.Manifest;
import com.snapsync.core.ManifestJson;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Fetches the published manifest over plain HTTP, without any Minecraft connection.
 *
 * <h2>Why this exists</h2>
 * NeoForge negotiates network channels at the very start of the configuration phase and
 * disconnects any client missing a non-optional channel. That happens before our
 * configuration task runs, so a client missing a networking mod can never be sent an offer
 * and can never be told what to download.
 *
 * <p>The only way around it is to download <i>before</i> connecting, which is why the manifest
 * is available as a plain HTTP resource: the multiplayer screen can query it, compare hashes
 * and hand the server files to the player while still on the title screen.
 */
public final class ManifestFetcher {

    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 10000;

    private ManifestFetcher() {
    }

    /** Outcome of a query, including the failure reason for display. */
    public record Result(Manifest manifest, String error) {
        public boolean ok() {
            return manifest != null && error == null;
        }
    }

    /**
     * Queries {@code http://host:port/manifest.json}.
     *
     * @param host server address as the player typed it, with or without a port
     * @param port the download port
     */
    public static Result fetch(String host, int port) {
        String url = "http://" + host + ":" + port + "/manifest.json";
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(true);
            connection.connect();

            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                // Reachable but nothing published yet: server started, snapshot not built.
                return new Result(null, Component.translatable("snapsync.error.not_published").getString());
            }
            if (code != HttpURLConnection.HTTP_OK) {
                return new Result(null, Component.translatable("snapsync.error.http", code).getString());
            }

            byte[] body;
            try (InputStream in = connection.getInputStream()) {
                body = in.readAllBytes();
            }

            Manifest manifest = ManifestJson.fromBytes(body);
            return new Result(manifest, null);
        } catch (java.net.ConnectException e) {
            return new Result(null,
                    Component.translatable("snapsync.error.connect", host + ":" + port).getString());
        } catch (java.net.SocketTimeoutException e) {
            return new Result(null, Component.translatable("snapsync.error.timeout").getString());
        } catch (Exception e) {
            SnapshotSync.LOGGER.warn("Manifest fetch failed for {}", url, e);
            return new Result(null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Works out the download port from a Minecraft address.
     *
     * <p>Uses {@code serverPort + offset} like the server does. When the server was reached on
     * the default port the result is predictable; otherwise the player may need to correct it,
     * which is why the caller surfaces the port it is about to try.
     */
    public static int guessPort(String serverIp) {
        return Config.resolveDownloadPort(parseMinecraftPort(serverIp));
    }

    /** Extracts the port from {@code host}, {@code host:port} or {@code [v6]:port}. */
    public static int parseMinecraftPort(String serverIp) {
        if (serverIp == null || serverIp.isBlank()) {
            return 25565;
        }

        String s = serverIp.trim();

        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close > 0 && close + 1 < s.length() && s.charAt(close + 1) == ':') {
                return safeParse(s.substring(close + 2), 25565);
            }
            return 25565;
        }

        int colon = s.lastIndexOf(':');
        // A single colon means host:port; multiple colons mean bare IPv6.
        if (colon > 0 && s.indexOf(':') == colon) {
            return safeParse(s.substring(colon + 1), 25565);
        }
        return 25565;
    }

    /** Strips the port, leaving just the host the HTTP request should target. */
    public static String hostOnly(String serverIp) {
        if (serverIp == null) {
            return "";
        }
        String s = serverIp.trim();

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

    private static int safeParse(String value, int fallback) {
        try {
            int port = Integer.parseInt(value.trim());
            return port > 0 && port <= 65535 ? port : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
