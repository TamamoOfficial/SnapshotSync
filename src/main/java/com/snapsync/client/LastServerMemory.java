package com.snapsync.client;

import com.snapsync.SnapshotSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Remembers which server the player last tried to reach, so the disconnect screen can offer
 * a download even when the connection never got far enough to know the address itself.
 *
 * <h2>Why this is needed</h2>
 * NeoForge negotiates channels at the start of the configuration phase and disconnects a client
 * that is missing a non-optional channel. At that point {@code Minecraft.getCurrentServer()}
 * returns {@code null}, because it reads from {@code ClientPacketListener}, which does not exist
 * until the play phase.
 *
 * <p>So the client cannot ask "which server did I just fail to join" - it has to have been
 * remembering it. {@link #noteConnectionAttempt} is called from the multiplayer screen before any
 * connection is made, and the value survives the disconnect, letting the disconnect screen offer
 * "download this server's pack now, and it will be complete after a restart".
 */
@EventBusSubscriber(modid = SnapshotSync.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class LastServerMemory {

    private static final Path FILE = Path.of(SnapshotSync.STATE, "last-server.txt");

    /** In-memory copy; also mirrored to disk so it survives a restart between attempts. */
    private static volatile String lastIp = "";
    private static volatile String lastName = "";

    private LastServerMemory() {
    }

    /** Called when the player initiates a connection, before any handshake happens. */
    public static void noteConnectionAttempt(ServerData server) {
        if (server == null || server.ip == null || server.ip.isBlank()) {
            return;
        }
        lastIp = server.ip;
        lastName = server.name == null ? server.ip : server.name;

        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, lastIp);
        } catch (IOException e) {
            SnapshotSync.LOGGER.debug("Could not persist last server", e);
        }
    }

    /** The server address to offer on the disconnect screen, or empty when unknown. */
    public static String lastIp() {
        if (!lastIp.isBlank()) {
            return lastIp;
        }
        // Fall back to the persisted value: the player may have restarted between the failed
        // join and looking at the screen.
        try {
            if (Files.isRegularFile(FILE)) {
                String stored = Files.readString(FILE).trim();
                if (!stored.isEmpty()) {
                    lastIp = stored;
                    lastName = stored;
                    return stored;
                }
            }
        } catch (IOException ignored) {
            // best effort
        }
        return "";
    }

    public static String lastName() {
        String ip = lastIp();
        return lastName.isBlank() ? ip : lastName;
    }

    /**
     * Adds a "download this server's pack" button to the disconnect screen.
     *
     * <p>This is the safety net for the exact case the channel negotiation prevents: the player
     * is missing a mod, cannot join, and needs a way to fix it without hunting for the address.
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof net.minecraft.client.gui.screens.DisconnectedScreen screen)) {
            return;
        }

        String ip = lastIp();
        if (ip.isBlank()) {
            return;
        }

        var button = net.minecraft.client.gui.components.Button
                .builder(Component.translatable("snapsync.disconnect.download"), b -> openUpdater(ip))
                .bounds(screen.width / 2 - 100, screen.height - 46, 200, 20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("snapsync.disconnect.hint")))
                .build();

        event.addListener(button);
    }

    private static void openUpdater(String ip) {
        Minecraft mc = Minecraft.getInstance();
        ServerData data = new ServerData(lastName(), ip, ServerData.Type.OTHER);
        mc.setScreen(new PreJoinUpdateScreen(data));
    }

    /** Nothing to clear on logout: we deliberately keep the address for the next attempt. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // Intentionally a no-op. The whole point is that the address outlives the connection.
    }
}
