package com.modsync.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;

import java.lang.reflect.Method;

/**
 * Records which server the player is about to join, purely as an address cache.
 *
 * <p>No UI is added to the multiplayer screen. The address is captured on click / Enter
 * because by the time a mod-mismatch disconnect happens, {@code getCurrentServer()} is null
 * and the address would otherwise be unrecoverable.
 */
public final class JoinWatcher {

    private JoinWatcher() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof JoinMultiplayerScreen multiplayer)) {
                return;
            }
            ScreenMouseEvents.afterMouseClick(multiplayer).register((s, event, intercepted) -> {
                remember(multiplayer);
                return false;
            });
            ScreenKeyboardEvents.afterKeyPress(multiplayer).register((s, event) -> {
                int key = event.key();
                if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                        || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                    remember(multiplayer);
                }
            });
        });
    }

    private static void remember(JoinMultiplayerScreen screen) {
        ServerData data = selected(screen);
        if (data != null) {
            ServerAddressMemory.remember(data);
        }
    }

    private static ServerData selected(JoinMultiplayerScreen screen) {
        try {
            var servers = screen.getServers();
            if (servers == null) {
                return null;
            }
            int index = selectedIndex(screen);
            if (index >= 0 && index < servers.size()) {
                return servers.get(index);
            }
            return servers.size() > 0 ? servers.get(0) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int selectedIndex(JoinMultiplayerScreen screen) {
        try {
            var field = JoinMultiplayerScreen.class.getDeclaredField("serverSelectionList");
            field.setAccessible(true);
            Object list = field.get(screen);
            if (list == null) {
                return -1;
            }
            for (Class<?> c = list.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod("getSelectedIndex");
                    m.setAccessible(true);
                    Object value = m.invoke(list);
                    return value instanceof Integer i ? i : -1;
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
