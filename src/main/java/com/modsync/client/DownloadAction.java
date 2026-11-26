package com.modsync.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Resolves where the download service is, preferring what the server told us.
 *
 * <p>Order: server-advertised endpoint, then the client config override, then a guess from the
 * joined address. The first is the normal path - the player never configures anything.
 */
public final class DownloadAction {

    private DownloadAction() {
    }

    /** Host the HTTP request should target. */
    public static String resolveHost() {
        if (RemoteEndpoint.known() && !RemoteEndpoint.host().isEmpty()) {
            return RemoteEndpoint.host();
        }
        if (!com.modsync.ModSyncConfig.clientAddress.isEmpty()) {
            return com.modsync.ModSyncConfig.clientAddress;
        }
        // Same machine as the server: reuse the address the player joined with.
        return ServerAddressMemory.host();
    }

    /** Port the download connection should target. */
    public static int resolvePort() {
        // 1. What the server advertised during the handshake - always correct.
        if (RemoteEndpoint.known() && RemoteEndpoint.port() > 0) {
            return RemoteEndpoint.port();
        }
        // 2. Explicit client override.
        int forced = com.modsync.ModSyncConfig.clientDownloadPort();
        if (forced > 0) {
            return forced;
        }
        // 3. Same port as the game when ModSync multiplexes it; otherwise game port + 1.
        int gamePort = ServerAddressMemory.port();
        if (com.modsync.ModSyncConfig.multiplex) {
            return gamePort;
        }
        return PackDownloader.downloadPort(gamePort);
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        String host = resolveHost();
        int port = resolvePort();
        if (host == null || host.isBlank()) {
            mc.setScreen(new ErrorScreen(Component.translatable(
                    "modsync.error.no_address").getString()));
            return;
        }
        if (port <= 0) {
            mc.setScreen(new ErrorScreen(Component.translatable(
                    "modsync.error.no_address").getString()));
            return;
        }
        mc.setScreen(new CheckingScreen(host, port));
    }
}
