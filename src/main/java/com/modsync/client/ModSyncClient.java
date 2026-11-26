package com.modsync.client;

import com.modsync.ModSync;
import com.modsync.net.DownloadInfoPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/** ModSync client entry point. */
public final class ModSyncClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        com.modsync.ModSyncConfig.load();
        ClientHooks.register();
        JoinWatcher.register();

        // The server announces its download endpoint before mod negotiation, so it is known even
        // when the client is about to be rejected.
        ClientConfigurationNetworking.registerGlobalReceiver(
                DownloadInfoPayload.TYPE,
                (payload, context) -> RemoteEndpoint.accept(payload));

        ClientConfigurationConnectionEvents.INIT.register((listener, client) ->
                ServerAddressMemory.capture());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                ServerAddressMemory.capture());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getConnection() != null) {
                ServerAddressMemory.capture();
            }
        });

        ModSync.LOGGER.info("ModSync client initialized");
    }
}
