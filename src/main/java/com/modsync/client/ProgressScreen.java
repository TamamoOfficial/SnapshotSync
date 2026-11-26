package com.modsync.client;

import com.modsync.ModSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Download progress screen: byte-accurate bar, current file, counts, and a cancel button.
 *
 * <p>Downloads a few hundred megabytes on a forwarded link, so a silent screen would look like
 * a freeze. Progress is published from the worker thread into volatile fields and read during
 * rendering.
 */
public class ProgressScreen extends Screen {

    private static final int BAR_W = 300;
    private static final int BAR_H = 10;

    private final String host;
    private final int port;
    private final List<PackDownloader.RemoteFile> files;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile PackDownloader.Progress progress;
    private volatile PackDownloader.Outcome outcome;
    private volatile Throwable error;

    public ProgressScreen(String host, int port, List<PackDownloader.RemoteFile> files) {
        super(Component.translatable("modsync.progress.title"));
        this.host = host;
        this.port = port;
        this.files = files;
    }

    @Override
    protected void init() {
        super.init();
        int by = this.height / 2 + 50;
        if (outcome != null || error != null) {
            addRenderableWidget(Button.builder(
                            Component.translatable("modsync.confirm.close"),
                            b -> onClose())
                    .bounds(this.width / 2 - 50, by, 100, 20)
                    .build());
        } else {
            addRenderableWidget(Button.builder(
                            Component.translatable("modsync.progress.cancel"),
                            b -> cancelled.set(true))
                    .bounds(this.width / 2 - 50, by, 100, 20)
                    .build());
        }
        // Start once, after the first layout so the screen is ready to be shown.
        if (progress == null && outcome == null && error == null) {
            start();
        }
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

    private void start() {
        Thread worker = new Thread(() -> {
            try {
                PackDownloader.Outcome result = PackDownloader.downloadAll(
                        host, port, files,
                        p -> this.progress = p,
                        cancelled::get);
                // Refresh the button row so it becomes "close".
                Minecraft.getInstance().execute(() -> {
                    this.outcome = result;
                    this.rebuildWidgets();
                });
            } catch (Throwable t) {
                ModSync.LOGGER.error("ModSync: download failed", t);
                Minecraft.getInstance().execute(() -> {
                    this.error = t;
                    this.rebuildWidgets();
                });
            }
        }, "ModSync-Download");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, this.width, this.height, 0xFF1E1E1E, 0xFF101010);

        int cx = this.width / 2;
        int y = this.height / 2 - 60;
        graphics.centeredText(this.font, this.title, cx, y, 0xFFFFFFFF);
        y += 24;

        PackDownloader.Progress p = progress;

        if (error != null) {
            graphics.centeredText(this.font,
                    Component.translatable("modsync.confirm.failed", String.valueOf(error.getMessage())),
                    cx, y, 0xFFFF5555);
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
            return;
        }

        if (outcome != null) {
            Component msg = outcome.cancelled()
                    ? Component.translatable("modsync.progress.cancelled", outcome.installed())
                    : Component.translatable("modsync.confirm.done", outcome.installed());
            // The message may contain newlines; draw each line separately so nothing renders
            // as a placeholder glyph.
            int color = outcome.cancelled() ? 0xFFFFAA00 : 0xFF55FF55;
            for (String part : msg.getString().split("\n")) {
                graphics.centeredText(this.font, Component.literal(part), cx, y, color);
                y += 12;
            }
            y += 20;
            graphics.centeredText(this.font,
                    Component.translatable("modsync.progress.summary",
                            outcome.installed(), outcome.skipped(), outcome.failed()),
                    cx, y, 0xFFAAAAAA);
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
            return;
        }

        float frac = p == null ? 0f : p.fraction();

        // Bar
        int barX = cx - BAR_W / 2;
        int barY = y + 8;
        graphics.fill(barX, barY, barX + BAR_W, barY + BAR_H, 0xFF3A3A3A);
        int filled = (int) (BAR_W * frac);
        if (filled > 0) {
            graphics.fill(barX, barY, barX + filled, barY + BAR_H, 0xFF2E8B2E);
        }
        graphics.fill(barX, barY, barX + BAR_W, barY + 1, 0xFF555555);

        y = barY + BAR_H + 10;
        graphics.centeredText(this.font,
                Component.literal(String.format("%.0f%%", frac * 100f)),
                cx, y, 0xFFFFFFFF);
        y += 14;

        if (p != null) {
            graphics.centeredText(this.font,
                    Component.translatable("modsync.progress.files", p.done(), p.total()),
                    cx, y, 0xFFAAAAAA);
            y += 12;

            if (p.bytesTotal() > 0) {
                graphics.centeredText(this.font,
                        Component.literal(humanBytes(p.bytesDone()) + " / " + humanBytes(p.bytesTotal())),
                        cx, y, 0xFFAAAAAA);
                y += 12;
            }

            String current = p.current() == null ? "" : p.current();
            if (current.length() > 46) {
                current = "…" + current.substring(current.length() - 45);
            }
            graphics.centeredText(this.font, Component.literal(current), cx, y, 0xFF888888);
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private int safeTotal() {
        return files.size();
    }

    private static String humanBytes(long b) {
        if (b < 1024) {
            return b + " B";
        }
        if (b < 1024 * 1024) {
            return String.format("%.1f KiB", b / 1024.0);
        }
        return String.format("%.1f MiB", b / (1024.0 * 1024.0));
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // Esc cancels rather than silently closing mid-download.
        if (outcome == null && error == null) {
            cancelled.set(true);
            return false;
        }
        return true;
    }
}
