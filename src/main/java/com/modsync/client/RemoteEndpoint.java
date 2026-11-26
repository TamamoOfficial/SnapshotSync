package com.modsync.client;

import com.modsync.ModSync;
import com.modsync.net.DownloadInfoPayload;

/**
 * Holds the download endpoint the server announced during the configuration phase.
 *
 * <p>Kept in memory and mirrored to disk so it survives the disconnect that immediately follows
 * a mod-mismatch rejection - which is exactly when the download button is needed.
 */
public final class RemoteEndpoint {

    private static volatile String host = "";
    private static volatile int port = 0;
    private static volatile int fileCount = 0;

    private RemoteEndpoint() {
    }

    public static void accept(DownloadInfoPayload payload) {
        host = payload.host() == null ? "" : payload.host().trim();
        port = payload.port();
        fileCount = payload.fileCount();
        ModSync.LOGGER.info("ModSync: server advertises download endpoint {}:{} ({} files)",
                host.isEmpty() ? "<same host>" : host, port, fileCount);
    }

    /** Advertised host, or empty when the server did not specify one. */
    public static String host() {
        return host;
    }

    public static int port() {
        return port;
    }

    public static int fileCount() {
        return fileCount;
    }

    public static boolean known() {
        return port > 0;
    }
}
