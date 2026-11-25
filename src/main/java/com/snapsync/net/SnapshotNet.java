package com.snapsync.net;

import com.snapsync.SnapshotSync;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;

/**
 * Registration for the configuration-phase handshake.
 *
 * <p>Both events here are {@code IModBusEvent}s, so this class must be on the MOD bus.
 */
@EventBusSubscriber(modid = SnapshotSync.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class SnapshotNet {

    private SnapshotNet() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(final RegisterPayloadHandlersEvent event) {
        // optional(): a client without the mod is not kicked for a missing channel. We only
        // ever send to peers that advertise it, so this is purely defensive.
        event.registrar("1").optional().configurationBidirectional(
                SnapshotOfferPayload.TYPE,
                SnapshotOfferPayload.STREAM_CODEC,
                new DirectionalPayloadHandler<>(
                        (payload, context) -> {
                            // Clientbound: the client received our offer.
                            if (FMLEnvironment.dist.isClient()) {
                                context.enqueueWork(
                                        () -> com.snapsync.client.ClientOfferHandler.onOffer(payload));
                            }
                        },
                        (payload, context) -> {
                            // Never sent server-bound.
                        }
                )
        );
    }

    @SubscribeEvent
    public static void onRegisterConfigurationTasks(final RegisterConfigurationTasksEvent event) {
        // Only register when we actually have something to say; otherwise stay out of the
        // join path entirely.
        event.register(new SnapshotOfferTask(event.getListener()));
    }
}
