package com.modsync.net;

import com.modsync.ModSync;
import com.modsync.ModSyncConfig;
import com.modsync.server.ListingJson;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.ConfigurationTask;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Registers the configuration-phase handshake that publishes the download endpoint.
 *
 * <p>The task sends one packet and is completed immediately: the client does not need to answer,
 * it only has to remember where to fetch files from later.
 */
public final class DownloadInfoNet {

    public static final ConfigurationTask.Type TASK_TYPE =
            new ConfigurationTask.Type(Identifier.fromNamespaceAndPath("modsync", "info").toString());

    private DownloadInfoNet() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundConfiguration().register(
                DownloadInfoPayload.TYPE, DownloadInfoPayload.STREAM_CODEC);

        ServerConfigurationConnectionEvents.CONFIGURE.register((listener, server) -> {
            boolean canSend = ServerConfigurationNetworking.canSend(listener, DownloadInfoPayload.TYPE);
            ModSync.LOGGER.info("ModSync: configuration phase started, canSend={}", canSend);
            if (canSend) {
                ((net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationPacketListenerImpl) listener)
                        .addTask(new InfoTask(listener));
                ModSync.LOGGER.info("ModSync: queued download-info task");
            }
        });
    }

    /** Sends the endpoint once, then releases the configuration phase. */
    private static final class InfoTask implements ConfigurationTask {

        private final net.minecraft.server.network.ServerConfigurationPacketListenerImpl listener;

        private InfoTask(net.minecraft.server.network.ServerConfigurationPacketListenerImpl listener) {
            this.listener = listener;
        }

        @Override
        public void start(Consumer<Packet<?>> sender) {
            try {
                Path dir = Path.of(ModSync.MODS_DIR);
                int count = ListingJson.count(dir);

                String host = ModSyncConfig.publicHost;
                int port = ModSyncConfig.publicPort > 0
                        ? ModSyncConfig.publicPort
                        : ModSyncConfig.serverPort(25565);

                sender.accept(ServerConfigurationNetworking.createClientboundPacket(
                        new DownloadInfoPayload(host, port, count)));
                ModSync.LOGGER.info("Told client about download endpoint {}:{} ({} files)",
                        host.isEmpty() ? "<same host>" : host, port, count);
            } catch (Throwable t) {
                ModSync.LOGGER.error("Failed to send download info", t);
            } finally {
                // Critical: the configuration phase does not advance until the task is marked
                // complete. Omitting this leaves the client stuck on "Joining world".
                try {
                    ((net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationPacketListenerImpl)
                            listener).completeTask(TASK_TYPE);
                    ModSync.LOGGER.info("ModSync: configuration task completed");
                } catch (Throwable t) {
                    ModSync.LOGGER.error("ModSync: failed to complete configuration task", t);
                }
            }
        }

        @Override
        public Type type() {
            return TASK_TYPE;
        }
    }
}
