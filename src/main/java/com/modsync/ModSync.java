package com.modsync;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;

/**
 * ModSync - server-authored mod pack downloader (Fabric, MC 26.1.2).
 *
 * <p>The download service listens on its own TCP port and never touches the Minecraft port, so
 * it cannot interfere with the server starting. Transmission uses a custom protocol rather than
 * HTTP because some tunnel providers intercept HTTP traffic.
 */
public class ModSync implements ModInitializer {

    public static final String MODID = "modsync";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Server-side folder holding the jars to publish, relative to the game directory. */
    public static final String MODS_DIR = "config/modsync";

    @Override
    public void onInitialize() {
        LOGGER.info("ModSync server initializing");
        ModSyncConfig.load();
        com.modsync.net.DownloadInfoNet.register();

        com.modsync.server.DownloadServer.start();
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
                com.modsync.server.DownloadServer.stop());
    }
}
