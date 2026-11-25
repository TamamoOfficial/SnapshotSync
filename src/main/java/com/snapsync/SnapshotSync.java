package com.snapsync;

import com.snapsync.server.ServerHooks;
import com.snapsync.server.SnapshotReloadHook;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * SnapshotSync - server-authored, content-addressed pack snapshots.
 *
 * <p>Trigger model: the scheduler arms once when the server starts and again on every
 * {@code /reload}, then builds a single snapshot at the first idle+healthy moment and
 * disarms. See {@link SnapshotScheduler}.
 */
@Mod(SnapshotSync.MODID)
public class SnapshotSync {

    public static final String MODID = "snapsync";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final String DIR = "snapsync";
    public static final String REPO = DIR + "/repo";
    public static final String CACHE = DIR + "/cache";
    public static final String STATE = DIR + "/state";

    public SnapshotSync(ModContainer modContainer) {
        LOGGER.info("SnapshotSync loading");

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        NeoForge.EVENT_BUS.addListener(ServerHooks::onRegisterCommands);

        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) ->
                SnapshotScheduler.onServerStarted(e.getServer()));

        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) ->
                SnapshotScheduler.onServerStopping());

        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) ->
                SnapshotScheduler.onTick(e.getServer()));

        // /reload (and any datapack reload) re-arms: files may have changed underneath us.
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent e) ->
                SnapshotReloadHook.onReload());
    }
}
