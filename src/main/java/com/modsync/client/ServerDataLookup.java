package com.modsync.client;

import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;

import java.lang.reflect.Method;

/** Reads the highlighted server row from a JoinMultiplayerScreen so we know which host to query. */
final class ServerDataLookup {

    private ServerDataLookup() {
    }

    static void rememberSelected(JoinMultiplayerScreen screen) {
        ServerData data = selected(screen);
        if (data != null) {
            ServerAddressMemory.remember(data);
        }
    }

    static ServerData selected(JoinMultiplayerScreen screen) {
        int index = index(screen);
        if (screen.getServers() == null) {
            return null;
        }
        if (index >= 0 && index < screen.getServers().size()) {
            return screen.getServers().get(index);
        }
        return screen.getServers().size() == 0 ? null : screen.getServers().get(0);
    }

    private static int index(JoinMultiplayerScreen screen) {
        try {
            var field = JoinMultiplayerScreen.class.getDeclaredField("serverSelectionList");
            field.setAccessible(true);
            Object list = field.get(screen);
            if (list == null) {
                return -1;
            }
            Method m = null;
            for (Class<?> c = list.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    m = c.getDeclaredMethod("getSelectedIndex");
                    m.setAccessible(true);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (m == null) {
                return -1;
            }
            Object value = m.invoke(list);
            return value instanceof Integer i ? i : -1;
        } catch (Throwable t) {
            return -1;
        }
    }
}
