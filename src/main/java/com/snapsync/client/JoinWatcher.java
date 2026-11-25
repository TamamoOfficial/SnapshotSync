package com.snapsync.client;

import com.snapsync.SnapshotSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Records the server a player is about to join.
 *
 * <p>Minecraft has no "connection starting" event, and by the time a connection fails the
 * address is already gone: {@code Minecraft.getCurrentServer()} reads from
 * {@code ClientPacketListener}, which does not exist until the play phase, so it is null for
 * exactly the failure we care about (channel negotiation).
 *
 * <p>The address is therefore captured while the multiplayer screen still holds it.
 */
@EventBusSubscriber(modid = SnapshotSync.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class JoinWatcher {

    private JoinWatcher() {
    }

    /**
     * Any click while the multiplayer screen is up remembers the current selection.
     *
     * <p>Capture on every click rather than trying to detect the join gesture: NeoForge's
     * mouse event does not expose the click count, and remembering on selection is strictly
     * more reliable - by the time a join starts, the selection is always already recorded.
     */
    @SubscribeEvent
    public static void onMouse(ScreenEvent.MouseButtonPressed.Post event) {
        if (!(event.getScreen() instanceof JoinMultiplayerScreen screen)) {
            return;
        }
        remember(screen);
    }

    /** Enter joins the selected server, so remember it first. */
    @SubscribeEvent
    public static void onKey(ScreenEvent.KeyPressed.Post event) {
        if (!(event.getScreen() instanceof JoinMultiplayerScreen screen)) {
            return;
        }
        int key = event.getKeyCode();
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            remember(screen);
        }
    }

    private static void remember(JoinMultiplayerScreen screen) {
        ServerData server = selected(screen);
        if (server != null) {
            LastServerMemory.noteConnectionAttempt(server);
        }
    }

    static ServerData selected(JoinMultiplayerScreen screen) {
        int index = SelectedRow.index(screen);
        if (index >= 0 && index < screen.getServers().size()) {
            return screen.getServers().get(index);
        }
        return screen.getServers().size() > 0 ? screen.getServers().get(0) : null;
    }
}
