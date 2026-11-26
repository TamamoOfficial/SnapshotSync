package com.modsync.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.network.chat.Component;

/**
 * A centered block of text lines rendered through the normal widget path.
 *
 * <p>In 26.x custom drawing inside {@code extractRenderState} does not reliably reach the
 * frame, so the text is published as a plain {@link Renderable} widget instead.
 */
final class TextBlock implements Renderable {

    private final Component title;
    private final Component[] lines;
    private final int color;
    private final int titleColor;

    TextBlock(Component title, Component[] lines, int titleColor, int color) {
        this.title = title;
        this.lines = lines;
        this.titleColor = titleColor;
        this.color = color;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        if (font == null) {
            return;
        }
        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int h = mc.getWindow().getGuiScaledHeight();
        int y = h / 2 - 60;

        if (title != null) {
            graphics.centeredText(font, title, cx, y, titleColor);
            y += 20;
        }
        for (Component line : lines) {
            graphics.centeredText(font, line, cx, y, color);
            y += 12;
        }
    }
}
