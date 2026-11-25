package com.snapsync.server;

import com.snapsync.Config;
import com.snapsync.PackMode;
import com.snapsync.SnapshotScheduler;
import com.snapsync.core.Manifest;
import com.snapsync.core.SnapshotBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Operator commands.
 *
 * <p>All output goes through translation keys so the same build speaks Chinese or English
 * depending on the server's configured language.
 */
public final class ServerHooks {

    private ServerHooks() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("snapsync")
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("rebuild")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> {
                            SnapshotScheduler.forceRebuild();
                            ctx.getSource().sendSuccess(
                                    () -> Component.translatable("snapsync.command.rebuild_queued"),
                                    true);
                            return 1;
                        }))
                .then(Commands.literal("port")
                        .executes(ctx -> {
                            var server = ctx.getSource().getServer();
                            int mc = server.getPort();
                            int dl = Config.resolveDownloadPort(mc);
                            Component suffix = Config.fixedDownloadPort > 0
                                    ? Component.translatable("snapsync.command.port_fixed")
                                    : Component.translatable("snapsync.command.port_offset",
                                            Config.downloadPortOffset);
                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                    "snapsync.command.port_info", mc, dl, suffix), false);
                            return 1;
                        }))
        );
    }

    private static int status(CommandSourceStack source) {
        Manifest current = SnapshotBuilder.current();
        var server = source.getServer();
        int dlPort = Config.resolveDownloadPort(server.getPort());

        source.sendSuccess(() -> Component.translatable("snapsync.status.header"), false);

        if (current == null) {
            source.sendSuccess(() -> Component.translatable("snapsync.status.snapshot_none"), false);
        } else {
            source.sendSuccess(() -> Component.translatable("snapsync.status.snapshot",
                    current.snapshotId(), current.files().size()), false);
            source.sendSuccess(() -> Component.translatable("snapsync.status.root",
                    current.rootHash()), false);
        }

        source.sendSuccess(() -> Component.translatable("snapsync.status.port",
                server.getPort(), dlPort), false);

        source.sendSuccess(() -> Component.translatable("snapsync.status.players",
                server.getPlayerList().getPlayerCount(),
                String.format(java.util.Locale.ROOT, "%.2f", SnapshotScheduler.tps(server))), false);

        source.sendSuccess(() -> Component.translatable("snapsync.status.mode",
                modeDescription()), false);

        var changes = SnapshotScheduler.lastChangeSummary();
        if (changes.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("snapsync.status.last_none"), false);
        } else {
            source.sendSuccess(() -> Component.translatable("snapsync.status.last_summary",
                    changes.describe()), false);

            // Show up to a handful of paths; the rest is in the log.
            int shown = 0;
            for (String p : changes.added()) {
                if (shown++ >= 5) break;
                source.sendSuccess(() -> Component.literal("  + " + p), false);
            }
            for (String p : changes.modified()) {
                if (shown++ >= 5) break;
                source.sendSuccess(() -> Component.literal("  ~ " + p), false);
            }
            for (String p : changes.removed()) {
                if (shown++ >= 5) break;
                source.sendSuccess(() -> Component.literal("  - " + p), false);
            }
            if (changes.total() > 5) {
                source.sendSuccess(() -> Component.translatable("snapsync.status.more_changes",
                        changes.total() - 5), false);
            }
        }

        if (SnapshotScheduler.isArmed()) {
            source.sendSuccess(() -> Component.translatable("snapsync.status.trigger_armed",
                    SnapshotScheduler.armedReason()), false);
        } else {
            source.sendSuccess(() -> Component.translatable("snapsync.status.trigger_idle"), false);
        }

        return 1;
    }

    /** Localised description of the active packing mode and its next action. */
    private static Component modeDescription() {
        if (Config.packMode == PackMode.INTERVAL) {
            long now = System.currentTimeMillis();
            long intervalMillis = Config.intervalMinutes * 60_000L;
            long next = SnapshotScheduler.lastBuildMillis() + intervalMillis;
            long remaining = Math.max(0, next - now);
            long minutes = remaining / 60_000L;
            long seconds = (remaining % 60_000L) / 1000L;
            return Component.translatable("snapsync.status.mode_interval",
                    Config.intervalMinutes, minutes, seconds);
        }
        return Component.translatable("snapsync.status.mode_idle",
                Config.idleDelaySeconds, Config.minTpsForSnapshot);
    }
}
