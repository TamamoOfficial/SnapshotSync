package com.modsync.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Brief "checking server..." screen while the jar list is fetched. */
public class CheckingScreen extends Screen {

    private final String host;
    private final int port;
    private boolean started;

    public CheckingScreen(String host, int port) {
        super(Component.translatable("modsync.checking.title"));
        this.host = host;
        this.port = port;
    }

    @Override
    protected void init() {
        super.init();
        if (!started) {
            started = true;
            // The port was already resolved by DownloadAction; do not add an offset here.
            int downloadPort = port;
            Thread worker = new Thread(() -> {
                PackDownloader.Result result = PackDownloader.list(host, downloadPort);
                this.minecraft.execute(() -> {
                    if (!result.ok()) {
                        this.minecraft.setScreen(new ErrorScreen(result.error()));
                    } else if (result.files().isEmpty()) {
                        this.minecraft.setScreen(new ErrorScreen("No mods published by server"));
                    } else {
                        this.minecraft.setScreen(new ConfirmScreen(host, downloadPort, result.files()));
                    }
                });
            }, "ModSync-Check");
            worker.setDaemon(true);
            worker.start();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, this.width, this.height, 0xFF1E1E1E, 0xFF101010);
        graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2, 0xFFFFFFFF);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void repositionElements() {
        // No widgets; nothing to reposition.
    }
}
