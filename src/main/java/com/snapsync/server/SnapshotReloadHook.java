package com.snapsync.server;

import com.snapsync.SnapshotScheduler;
import com.snapsync.SnapshotSync;

/**
 * Re-arms the snapshot scheduler after a resource reload.
 *
 * <p>{@code AddReloadListenerEvent} fires on {@code /reload} and on datapack reloads. That is
 * exactly the moment files may have changed on disk, so the next idle window should produce a
 * fresh snapshot.
 */
public final class SnapshotReloadHook {

    private SnapshotReloadHook() {
    }

    public static void onReload() {
        SnapshotSync.LOGGER.info("Reload detected; re-arming snapshot scheduler");
        SnapshotScheduler.arm("reload");
    }
}
