package com.modsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ModSync settings, stored in {@code config/modsync.json}.
 *
 * <h2>Two serving modes</h2>
 * <b>Separate port (default)</b> - the HTTP service binds its own port ({@code listenPort},
 * or game port + 1). The admin forwards that port separately.
 *
 * <p><b>Multiplexed</b> - set {@code multiplex = true}. ModSync then owns {@code listenPort}
 * (typically the public game port) and inspects the first bytes of every connection: Minecraft
 * handshakes are relayed to {@code gameTargetPort}, HTTP requests are answered in-process. This
 * lets a single forwarded port serve both the game and the mod downloads, which is what NAT /
 * FRP setups with one published port need.
 *
 * <p>In multiplexed mode the real Minecraft server must listen on {@code gameTargetPort}, so
 * {@code server-port} (or the zstd proxy's {@code listen}) has to be moved off {@code listenPort}.
 */
public final class ModSyncConfig {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Path FILE = Path.of("config", "modsync.json");

    private static final class Data {
        boolean multiplex = false;
        int listenPort = 0;
        int gameTargetPort = 0;
        String publicHost = "";
        int publicPort = 0;
        String clientAddress = "";
        int clientPort = 0;
    }

    /** When true, one port serves both game traffic and downloads. */
    public static boolean multiplex = false;
    /** Port ModSync binds. 0 = game port + 1 (separate mode only). */
    public static int listenPort = 0;
    /** Multiplexed mode: where real Minecraft traffic is relayed. 0 = game port + 2. */
    public static int gameTargetPort = 0;
    /** Address clients should use; empty means "reuse the address they connected to". */
    public static String publicHost = "";
    /** Port clients should use; 0 means "same as the listen port". */
    public static int publicPort = 0;
    /** Client-side forced address (rarely needed). */
    public static String clientAddress = "";
    /** Client-side forced port (rarely needed). */
    public static int clientPort = 0;

    private ModSyncConfig() {
    }

    public static void load() {
        Data data;
        if (Files.isRegularFile(FILE)) {
            try {
                data = GSON.fromJson(Files.readString(FILE), Data.class);
            } catch (Exception e) {
                LOGGER.warn("Could not read {}, using defaults", FILE, e);
                data = null;
            }
        } else {
            data = new Data();
        }
        if (data == null) {
            data = new Data();
        }

        multiplex = data.multiplex;
        listenPort = clampPort(data.listenPort);
        gameTargetPort = clampPort(data.gameTargetPort);
        publicHost = data.publicHost == null ? "" : data.publicHost.trim();
        publicPort = clampPort(data.publicPort);
        clientAddress = data.clientAddress == null ? "" : data.clientAddress.trim();
        clientPort = clampPort(data.clientPort);

        save();
        LOGGER.info("ModSync config: multiplex={} listen={} gameTarget={} publicHost='{}' publicPort={}",
                multiplex,
                listenPort == 0 ? "<game+1>" : String.valueOf(listenPort),
                gameTargetPort == 0 ? "<game+2>" : String.valueOf(gameTargetPort),
                publicHost.isEmpty() ? "<auto>" : publicHost,
                publicPort == 0 ? "<listen>" : String.valueOf(publicPort));
    }

    private static int clampPort(int value) {
        return value > 0 && value <= 65535 ? value : 0;
    }

    private static void save() {
        Data data = new Data();
        data.multiplex = multiplex;
        data.listenPort = listenPort;
        data.gameTargetPort = gameTargetPort;
        data.publicHost = publicHost;
        data.publicPort = publicPort;
        data.clientAddress = clientAddress;
        data.clientPort = clientPort;
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(data));
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", FILE, e);
        }
    }

    /** Local port ModSync binds. In multiplexed mode this is the public-facing port. */
    public static int serverPort(int gamePort) {
        if (listenPort > 0) {
            return listenPort;
        }
        int derived = gamePort + 1;
        return derived > 65535 ? gamePort - 1 : derived;
    }

    /** Multiplexed mode: where Minecraft traffic is relayed to. */
    public static int relayTargetPort(int gamePort) {
        if (gameTargetPort > 0) {
            return gameTargetPort;
        }
        int derived = gamePort + 2;
        return derived > 65535 ? gamePort - 2 : derived;
    }

    /** Port advertised to clients. */
    public static int advertisedPort(int gamePort) {
        if (publicPort > 0) {
            return publicPort;
        }
        return serverPort(gamePort);
    }

    /** Client-side override, or 0 to trust the server's advertised port. */
    public static int clientDownloadPort() {
        return clientPort;
    }
}
