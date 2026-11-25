package com.snapsync.client;

import com.snapsync.SnapshotSync;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Client lifecycle: report deferred updates, and hand off to the installer on shutdown.
 *
 * <h2>Why the shutdown hook matters</h2>
 * Mod jars are held open by the classloader, so they cannot be replaced while the game runs
 * (on Windows even a rename is refused). The update is therefore staged during play and
 * installed by a detached helper once the JVM has actually exited. This class is what starts
 * that helper - if the player kills the process instead of quitting, the helper still runs,
 * because it waits for the pid rather than being told to start.
 */
@EventBusSubscriber(modid = SnapshotSync.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientHooks {

    private static boolean announcedPending;
    private static boolean hookInstalled;

    private ClientHooks() {
    }

    /** Clear per-server state so an offer from one server cannot leak into another. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientOfferHandler.clear();
        announcedPending = false;
    }

    /** Tell the player once, shortly after joining, that a restart will finish the update. */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        installShutdownHook();

        if (announcedPending || mc.player == null) {
            return;
        }

        if (DeferredApplier.hasPendingUpdate()) {
            announcedPending = true;
            mc.player.sendSystemMessage(Component.translatable("snapsync.chat.staged_pending"));
        }
    }

    /**
     * Registers a JVM shutdown hook that launches the install helper.
     *
     * <p>A shutdown hook is used rather than the mod's own unload event because it also covers
     * the case where the player quits from the main menu, and it runs late enough that the
     * classloader no longer matters - the helper waits for the process to disappear anyway.
     */
    private static synchronized void installShutdownHook() {
        if (hookInstalled) {
            return;
        }
        hookInstalled = true;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                // Show what happened last time, before this session's update runs.
                String last = DeferredApplier.lastResult();
                if (!last.isEmpty()) {
                    SnapshotSync.LOGGER.info("Previous deferred update log:\n{}", last);
                }

                DeferredApplier.launchOnExit();
            } catch (Throwable t) {
                SnapshotSync.LOGGER.error("Could not launch the deferred update helper", t);
            }
        }, "SnapshotSync-ShutdownHook"));
    }

    /** Summary for the client command. */
    public static void printStatus() {
        Minecraft mc = Minecraft.getInstance();
        var pending = ClientOfferHandler.get();
        String local = LocalState.rootHash();

        line(mc, Component.translatable("snapsync.status.client_header"));
        line(mc, Component.translatable("snapsync.status.applied_root",
                local.isEmpty()
                        ? Component.translatable("snapsync.status.nothing_yet")
                        : Component.literal(local)));

        if (pending == null) {
            line(mc, Component.translatable("snapsync.status.no_pending"));
        } else {
            line(mc, Component.translatable("snapsync.status.pending",
                    pending.manifest().files().size(),
                    pending.host(),
                    pending.port()));
            line(mc, Component.translatable("snapsync.status.server_root",
                    pending.manifest().rootHash()));
            line(mc, Component.translatable("snapsync.status.pull_hint"));
        }

        if (DeferredApplier.hasPendingUpdate()) {
            line(mc, Component.translatable("snapsync.status.staged_yes"));
            String log = DeferredApplier.lastResult();
            if (!log.isEmpty()) {
                line(mc, Component.translatable("snapsync.status.last_install"));
                for (String l : log.split("\n")) {
                    line(mc, Component.literal("  " + l));
                }
            }
        } else {
            line(mc, Component.translatable("snapsync.status.staged_no"));
        }
    }


    private static void line(Minecraft mc, Component text) {
        if (mc.player != null) {
            mc.player.sendSystemMessage(text);
        }
    }
}
