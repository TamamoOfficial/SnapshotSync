package com.modsync.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.chat.Component;

/**
 * Adds the download button to the disconnect screen.
 *
 * <p>No configuration is needed on the client: the endpoint comes from the server over the
 * configuration-phase handshake, so the button simply works.
 */
public final class ClientHooks {

    private ClientHooks() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof DisconnectedScreen)) {
                return;
            }
            Button download = Button.builder(
                            Component.translatable("modsync.button.download"),
                            b -> DownloadAction.open())
                    .bounds(screen.width / 2 - 110, screen.height - 46, 220, 20)
                    .tooltip(Tooltip.create(Component.translatable("modsync.button.download.tip")))
                    .build();
            Screens.getWidgets(screen).add(download);
        });
    }
}
