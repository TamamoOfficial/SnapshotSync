package com.snapsync.client;

import com.snapsync.SnapshotSync;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Client-side {@code /snapsync} commands. Registered separately from the server ones because
 * client commands live on a different event and use the client's own dispatcher.
 */
@EventBusSubscriber(modid = SnapshotSync.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientCommands {

    private ClientCommands() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("snapsync")
                .then(Commands.literal("status")
                        .executes(ctx -> {
                            ClientHooks.printStatus();
                            return 1;
                        }))
                .then(Commands.literal("pull")
                        .executes(ctx -> {
                            if (ClientOfferHandler.get() == null) {
                                ClientHooks.printStatus();
                                return 0;
                            }
                            SnapshotSyncer.startAsync();
                            return 1;
                        }))
        );
    }
}
