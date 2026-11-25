package com.snapsync.net;

import com.snapsync.Config;
import com.snapsync.SnapshotSync;
import com.snapsync.core.Manifest;
import com.snapsync.core.ManifestJson;
import com.snapsync.core.SnapshotBuilder;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConfigurationTask;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.function.Consumer;

/**
 * Configuration-phase task that hands the client the current snapshot manifest.
 *
 * <p>Using a configuration task rather than a play-phase packet means this runs on every
 * join, before the world loads, and it gives us a defined end point via
 * {@code finishCurrentTask} so the client never waits on us indefinitely.
 */
public record SnapshotOfferTask(ServerConfigurationPacketListener listener)
        implements ICustomConfigurationTask {

    public static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type("snapsync:offer");

    @Override
    public void run(Consumer<CustomPacketPayload> sender) {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            int mcPort = server != null ? server.getPort() : 25565;
            int downloadPort = Config.resolveDownloadPort(mcPort);

            Manifest current = SnapshotBuilder.current();
            if (current == null) {
                SnapshotSync.LOGGER.info("No snapshot published yet; sending empty offer");
                sender.accept(SnapshotOfferPayload.empty(downloadPort));
            } else {
                byte[] json = ManifestJson.toBytes(current);
                SnapshotSync.LOGGER.info("Offering snapshot {} ({} files, root {}) on port {}",
                        current.snapshotId(), current.files().size(), current.rootHash(), downloadPort);
                sender.accept(new SnapshotOfferPayload(
                        current.snapshotId(),
                        current.rootHash(),
                        downloadPort,
                        json.length,
                        json
                ));
            }
        } catch (Throwable t) {
            SnapshotSync.LOGGER.error("Failed to send snapshot offer", t);
            sender.accept(SnapshotOfferPayload.empty(25565));
        } finally {
            // Always release the configuration phase, even if we failed.
            listener.finishCurrentTask(TYPE);
        }
    }

    @Override
    public ConfigurationTask.Type type() {
        return TYPE;
    }
}
