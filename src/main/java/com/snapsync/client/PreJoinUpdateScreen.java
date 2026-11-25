package com.snapsync.client;

import com.snapsync.Config;
import com.snapsync.SnapshotSync;
import com.snapsync.core.ChangeSummary;
import com.snapsync.core.Manifest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-join updater: checks the server's published manifest over HTTP and installs it.
 *
 * <h2>Why this screen exists at all</h2>
 * NeoForge negotiates network channels at the start of the configuration phase and disconnects
 * any client missing a non-optional channel. That happens before any configuration task runs,
 * so an in-game offer can never reach a client that is missing a networking mod. The only way
 * to update such a client is to do it <b>before</b> connecting - which is what this screen is
 * for.
 *
 * <p>It needs nothing but the address: the manifest is a plain HTTP resource, so no Minecraft
 * connection, no login and no channel negotiation are involved.
 */
public class PreJoinUpdateScreen extends Screen {

    private enum Step { CHECKING, RESULT, WORKING, DONE }

    final ServerData server;
    final String host;
    int port;

    private Step step = Step.CHECKING;
    private String status = "";
    private String error;

    private Manifest remote;
    private ChangeSummary differences = ChangeSummary.empty();
    private int toDownload;
    private long downloadBytes;

    private Button primaryButton;
    private Button closeButton;
    private Button portButton;

    public PreJoinUpdateScreen(ServerData server) {
        super(Component.translatable("snapsync.screen.title"));
        this.server = server;
        this.host = ManifestFetcher.hostOnly(server == null ? "" : server.ip);
        this.port = ManifestFetcher.guessPort(server == null ? "" : server.ip);
        this.status = Component.translatable("snapsync.update.checking",
                this.host + ":" + this.port).getString();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        buildButtons();
        if (step == Step.CHECKING && remote == null && error == null) {
            startCheck();
        }
    }

    private void recheck() {
        step = Step.CHECKING;
        error = null;
        remote = null;
        toDownload = 0;
        downloadBytes = 0;
        status = Component.translatable("snapsync.update.checking", host + ":" + port).getString();
        this.rebuildWidgets();
        startCheck();
    }


    /**
     * Y coordinate of a button row counted from the bottom of the screen.
     *
     * <p>Row 0 is the lowest button. Pinning buttons to the bottom keeps them out of the way of
     * the file list, which grows downwards and used to overlap them.
     */
    private int bottomRow(int index) {
        return this.height - 30 - index * 24;
    }

    /** Top edge of the button area, used to bound the scrollable-looking text region. */
    private int buttonBlockTop() {
        int rows = step == Step.RESULT ? 3 : 1;
        return bottomRow(rows - 1) - 8;
    }

    private void buildButtons() {
        int cx = this.width / 2;

        switch (step) {
            case RESULT -> {
                boolean hasWork = error == null && toDownload > 0;

                primaryButton = Button.builder(
                                Component.translatable(hasWork
                                ? "snapsync.update.download_install"
                                : "snapsync.update.recheck"),
                                b -> {
                                    if (hasWork) {
                                        beginDownload();
                                    } else {
                                        recheck();
                                    }
                                })
                        .bounds(cx - 110, bottomRow(2), 220, 20).build();
                addRenderableWidget(primaryButton);

                portButton = Button.builder(
                                Component.translatable("snapsync.update.port", port),
                                b -> this.minecraft.setScreen(new PortPromptScreen(this, port)))
                        .bounds(cx - 110, bottomRow(1), 220, 20)
                        .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                                Component.translatable("snapsync.update.port.tooltip")))
                        .build();
                addRenderableWidget(portButton);

                closeButton = Button.builder(Component.translatable("snapsync.update.close"), b -> onClose())
                        .bounds(cx - 110, bottomRow(0), 220, 20).build();
                addRenderableWidget(closeButton);
            }
            case DONE -> {
                closeButton = Button.builder(Component.translatable("snapsync.update.close"), b -> onClose())
                        .bounds(cx - 110, bottomRow(0), 220, 20).build();
                addRenderableWidget(closeButton);
            }
            default -> {
                closeButton = Button.builder(Component.translatable("snapsync.update.cancel"), b -> onClose())
                        .bounds(cx - 110, bottomRow(0), 220, 20).build();
                addRenderableWidget(closeButton);
            }
        }
    }

    // ------------------------------------------------------------------- check

    private void startCheck() {
        Thread t = new Thread(() -> {
            ManifestFetcher.Result result = ManifestFetcher.fetch(host, port);

            this.minecraft.execute(() -> {
                if (!result.ok()) {
                    step = Step.RESULT;
                    error = result.error();
                    status = Component.translatable("snapsync.update.fetch_failed").getString();
                    this.rebuildWidgets();
                    return;
                }

                remote = result.manifest();
                Manifest local = LocalState.load();
                String localRoot = local == null ? "" : local.rootHash();

                if (erroneousLocal(remote, localRoot)) {
                    step = Step.RESULT;
                    error = null;
                    status = Component.translatable("snapsync.update.up_to_date").getString();
                    toDownload = 0;
                    this.rebuildWidgets();
                    return;
                }

                computeDifferences(local);
                step = Step.RESULT;
                status = Component.translatable("snapsync.update.found", toDownload).getString();
                this.rebuildWidgets();
            });
        }, "SnapshotSync-Check");
        t.setDaemon(true);
        t.start();
    }

    private boolean erroneousLocal(Manifest remote, String localRoot) {
        return remote.rootHash().equals(localRoot);
    }

    /** Which files the server has that we do not (or have different). */
    private void computeDifferences(Manifest local) {
        Map<String, String> have = local == null ? Map.of() : local.files();
        List<String> changed = new ArrayList<>();

        for (Map.Entry<String, String> e : remote.files().entrySet()) {
            if (!e.getValue().equals(have.get(e.getKey()))) {
                changed.add(e.getKey());
            }
        }

        toDownload = changed.size();

        // Rough size estimate is not available without asking the server for each blob, so we
        // only report the file count; the progress bar covers the real transfer.
        differences = new ChangeSummary(
                new java.util.TreeSet<>(changed), new java.util.TreeSet<>(), new java.util.TreeSet<>());
    }

    // ---------------------------------------------------------------- download

    private void beginDownload() {
        step = Step.WORKING;
        status = Component.translatable("snapsync.update.downloading").getString();
        this.rebuildWidgets();

        ClientOfferHandler.PendingUpdate pending =
                new ClientOfferHandler.PendingUpdate(host, port, remote, LocalState.rootHash());

        SnapshotSyncer.startAsync(pending);

        // Poll for completion; the syncer runs on its own thread.
        Thread watcher = new Thread(() -> {
            try {
                while (SnapshotSyncer.isRunning()) {
                    Thread.sleep(200);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            this.minecraft.execute(() -> {
                step = Step.DONE;
                Manifest local = LocalState.load();
                boolean upToDate = local != null && remote != null
                        && local.rootHash().equals(remote.rootHash());
                status = Component.translatable(upToDate
                        ? "snapsync.update.done"
                        : "snapsync.update.incomplete").getString();
                this.rebuildWidgets();
            });
        }, "SnapshotSync-Watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    // ----------------------------------------------------------------- render

    /**
     * Draws the screen background without the blur.
     *
     * <p>{@code Screen.renderBackground} calls {@code renderBlurredBackground} (a full-screen
     * post-process blur) and then {@code renderMenuBackground}. Vanilla does that so text
     * stays readable over the world, but on this screen the blur made the list of files hard
     * to read while the download is supposedly transparent about what it touches.
     *
     * <p>Leaving out only the blur is deliberate: the panorama / menu texture and the
     * translucency still come from vanilla, so the screen keeps looking like Minecraft
     * instead of turning into a flat colour.
     */
    protected void renderFlatBackground(GuiGraphics graphics, float partialTick) {
        // Fully opaque backdrop, and deliberately no blur pass.
        //
        // The screen is opened from the server list, so the previous screen is still in the
        // frame buffer. Vanilla's renderBackground blurs it and draws only a translucent
        // gradient over it, which leaves that list legible-but-smeared underneath our own
        // text - two overlapping layers of text is what made this unreadable.
        //
        // An opaque fill removes the old layer entirely, so only this screen's text is visible.
        graphics.fillGradient(0, 0, this.width, this.height, 0xFF1E1E1E, 0xFF101010);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderFlatBackground(graphics, partialTick);

        int cx = this.width / 2;
        int y = this.height / 9;

        graphics.drawCenteredString(this.font, this.title, cx, y, 0xFFFFFF);

        String target = server == null
                ? Component.translatable("snapsync.update.no_server").getString()
                : Component.translatable("snapsync.update.target", server.name, server.ip).getString();
        graphics.drawCenteredString(this.font, Component.literal(target), cx, y + 16, 0xAAAAAA);
        graphics.drawCenteredString(this.font,
                Component.literal("manifest: http://" + host + ":" + port + "/manifest.json"),
                cx, y + 30, 0x888888);

        graphics.drawCenteredString(this.font, Component.literal(status), cx, y + 60, 0xFFFFFF);

        if (error != null) {
            int ey = y + 76;
            for (String line : wrap(error, 60)) {
                graphics.drawCenteredString(this.font, Component.literal(line), cx, ey, 0xFF5555);
                ey += 12;
            }
        }

        if (step == Step.RESULT && error == null && toDownload > 0) {
            int ly = y + 76;

            // Only list as many rows as fit above the button block, so text never runs under
            // the buttons on small GUI scales.
            int roomRows = Math.max(1, (buttonBlockTop() - ly) / 11);
            int budget = Math.min(8, roomRows);

            int shown = 0;
            for (String path : differences.added()) {
                if (shown++ >= budget) break;
                graphics.drawCenteredString(this.font, Component.literal("+ " + path), cx, ly, 0x55FF55);
                ly += 11;
            }
            if (toDownload > shown) {
                graphics.drawCenteredString(this.font,
                        Component.translatable("snapsync.update.more_files", toDownload - shown),
                        cx, ly, 0xAAAAAA);
            }
        }

        if (step == Step.WORKING) {
            int barW = 240;
            int barX = cx - barW / 2;
            // Sit the bar just above the button block so it never touches the buttons.
            int barY = buttonBlockTop() - 24;
            graphics.fill(barX, barY, barX + barW, barY + 10, 0xFF555555);
            // Indeterminate: the syncer does not report granular progress to the screen.
            long t = System.currentTimeMillis() % 1000;
            int w = (int) (barW * (t / 1000.0));
            graphics.fill(barX, barY, barX + w, barY + 10, 0xFF55FF55);
        }

        // Deliberately NOT super.render(): Screen.render() calls renderBackground(), which
        // re-paints the blurred backdrop *on top of the text we just drew*. That is why the
        // text looked smeared while the buttons stayed sharp - the buttons are painted by the
        // widget loop, which happens after the background. Painting the widgets ourselves
        // keeps everything in one layer.
        for (var renderable : this.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        for (String part : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : part.split(" ")) {
                if (line.length() + word.length() + 1 > width) {
                    out.add(line.toString());
                    line = new StringBuilder();
                }
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
            out.add(line.toString());
        }
        return out;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(new net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(
                new net.minecraft.client.gui.screens.TitleScreen()));
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return step != Step.WORKING;
    }

    // ------------------------------------------------------- port override UI

    /** Lets the player correct the download port when the server is not using port+1. */
    static class PortPromptScreen extends Screen {

        private final PreJoinUpdateScreen parent;
        private final int current;

        PortPromptScreen(PreJoinUpdateScreen parent, int current) {
            super(Component.translatable("snapsync.port.title"));
            this.parent = parent;
            this.current = current;
        }

        @Override
        protected void init() {
            super.init();
            int cx = this.width / 2;

            for (int i = 0; i < 4; i++) {
                int value = current + i;
                addRenderableWidget(Button.builder(Component.translatable("snapsync.port.entry", value),
                        b -> {
                            PreJoinUpdateScreen p = new PreJoinUpdateScreen(parent.server);
                            p.port = value;
                            this.minecraft.setScreen(p);
                        }).bounds(cx - 100, this.height / 2 - 40 + i * 24, 200, 20).build());
            }

            addRenderableWidget(Button.builder(Component.translatable("snapsync.port.back"), b ->
                            this.minecraft.setScreen(parent))
                    .bounds(cx - 100, this.height / 2 + 60, 200, 20).build());
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // Own copy of the flat background: this class is static, so the outer screen's
            // instance method is not reachable from here.
            graphics.fillGradient(0, 0, this.width, this.height, 0xFF1E1E1E, 0xFF101010);

            graphics.drawCenteredString(this.font, this.title, this.width / 2,
                    this.height / 2 - 64, 0xFFFFFF);
            graphics.drawCenteredString(this.font,
                    Component.translatable("snapsync.port.hint"),
                    this.width / 2, this.height / 2 - 50, 0xAAAAAA);

            for (var renderable : this.renderables) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
        }
    }
}
