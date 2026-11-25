package com.snapsync.client;

import com.snapsync.SnapshotSync;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;

import java.lang.reflect.Method;

/**
 * Reads which row is highlighted in the multiplayer server list.
 *
 * <p>{@code ServerSelectionList$Entry} does not expose its {@code ServerData}, so the index has
 * to be read from the list itself. Reflection is used defensively: the field name is a
 * Minecraft internal, and if it ever changes the caller falls back to the first server instead
 * of crashing the screen.
 */
final class SelectedRow {

    private SelectedRow() {
    }

    /** @return the highlighted row index, or -1 when nothing is selected or it cannot be read. */
    static int index(JoinMultiplayerScreen screen) {
        try {
            var listField = JoinMultiplayerScreen.class.getDeclaredField("serverSelectionList");
            listField.setAccessible(true);
            Object list = listField.get(screen);
            if (list == null) {
                return -1;
            }

            Method getSelectedIndex = findMethod(list.getClass(), "getSelectedIndex");
            if (getSelectedIndex == null) {
                return -1;
            }

            Object value = getSelectedIndex.invoke(list);
            return value instanceof Integer i ? i : -1;
        } catch (Throwable t) {
            SnapshotSync.LOGGER.debug("Could not read the selected server row", t);
            return -1;
        }
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // keep walking up the hierarchy
            }
        }
        return null;
    }
}
