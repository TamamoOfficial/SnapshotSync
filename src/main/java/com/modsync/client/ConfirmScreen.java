package com.modsync.client;

import com.modsync.ModSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Confirmation screen shown after the player clicks "Download server mods".
 *
 * <p>Text is published as widgets (not drawn ad hoc) so it reaches the frame in 26.x, and
 * {@link #repositionElements()} rebuilds everything on resize.
 */
public class ConfirmScreen extends Screen {

    private final String host;
    private final int port;
    private final List<PackDownloader.RemoteFile> files;

    private Component status;
    private boolean busy;
    private boolean done;

    public ConfirmScreen(String host, int port, List<PackDownloader.RemoteFile> files) {
        super(Component.translatable("modsync.confirm.title"));
        this.host = host;
        this.port = port;
        this.files = files;
        this.status = Component.translatable("modsync.confirm.prompt", files.size());
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int by = this.height / 2 + 40;

        // Text first, so the buttons are drawn on top of it.
        addRenderableOnly(new TextBlock(null, wrap(status.getString()), 0xFFFFFFFF, 0xFFAAAAAA));

        if (done) {
            addRenderableWidget(Button.builder(
                            Component.translatable("modsync.confirm.close"),
                            b -> onClose())
                    .bounds(cx - 50, by, 100, 20)
                    .build());
            return;
        }

        Button yes = Button.builder(
                        Component.translatable("modsync.confirm.yes"),
                        b -> beginDownload())
                .bounds(cx - 105, by, 100, 20)
                .build();
        Button no = Button.builder(
                        Component.translatable("modsync.confirm.no"),
                        b -> onClose())
                .bounds(cx + 5, by, 100, 20)
                .build();
        yes.active = !busy;
        no.active = !busy;
        addRenderableWidget(yes);
        addRenderableWidget(no);
    }

    @Override
    protected void repositionElements() {
        if (this.minecraft != null) {
            this.rebuildWidgets();
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    private void beginDownload() {
        if (busy) {
            return;
        }
        ModSync.LOGGER.info("ModSync: opening download screen for {} file(s) from {}:{}",
                files.size(), host, port);
        // Hand off to the progress screen, which owns the worker thread and the cancel button.
        Minecraft.getInstance().setScreen(new ProgressScreen(host, port, files));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, this.width, this.height, 0xFF1E1E1E, 0xFF101010);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private Component[] wrap(String text) {
        List<Component> out = new ArrayList<>();
        for (String part : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : part.split(" ")) {
                if (line.length() + word.length() + 1 > 70) {
                    out.add(Component.literal(line.toString()));
                    line = new StringBuilder();
                }
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(word);
            }
            out.add(Component.literal(line.toString()));
        }
        return out.toArray(new Component[0]);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !busy;
    }
}
