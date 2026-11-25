package com.snapsync.client;

import com.snapsync.SnapshotSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Adds an "Update" button to the multiplayer screen.
 *
 * <p>This is the pre-join path and it is the one that matters: a client missing a networking
 * mod is disconnected during channel negotiation, before any in-game offer could arrive. The
 * button therefore works without connecting at all - it reads the manifest over plain HTTP.
 */
@EventBusSubscriber(modid = SnapshotSync.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientEventHandlers {

    private ClientEventHandlers() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof JoinMultiplayerScreen screen)) {
            return;
        }

        Button update = Button.builder(Component.translatable("snapsync.update.button"), b -> open(screen))
                .bounds(screen.width - 62, 6, 56, 20)
                .tooltip(Tooltip.create(
                        Component.translatable("snapsync.update.button.tooltip")))
                .build();

        event.addListener(update);
    }

    private static void open(JoinMultiplayerScreen screen) {
        Minecraft mc = Minecraft.getInstance();

        ServerData server = JoinWatcher.selected(screen);
        if (server == null) {
            return;
        }

        // Remember it too: if this connection is refused during channel negotiation, the
        // disconnect screen can still offer the download.
        LastServerMemory.noteConnectionAttempt(server);

        mc.setScreen(new PreJoinUpdateScreen(server));
    }
}
