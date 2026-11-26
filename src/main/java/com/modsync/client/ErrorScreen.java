package com.modsync.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Simple error screen with a back button. */
public class ErrorScreen extends Screen {

    private final String error;

    public ErrorScreen(String error) {
        super(Component.translatable("modsync.error.title"));
        this.error = error;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(
                        Component.translatable("modsync.confirm.close"),
                        b -> this.minecraft.setScreen(null))
                .bounds(this.width / 2 - 50, this.height / 2 + 40, 100, 20)
                .build());
    }

    @Override
    protected void repositionElements() {
        this.rebuildWidgets();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, this.width, this.height, 0xFF1E1E1E, 0xFF101010);
        int cx = this.width / 2;
        int cy = this.height / 2;
        graphics.centeredText(this.font, this.title, cx, cy - 30, 0xFFFF5555);
        graphics.centeredText(this.font, Component.literal(error), cx, cy, 0xFFAAAAAA);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
}
