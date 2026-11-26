package com.snapsync.client;

import com.snapsync.Config;
import com.snapsync.SnapshotSync;
import com.snapsync.core.Manifest;
import com.snapsync.core.ManifestJson;
import com.snapsync.net.SnapshotOfferPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

/**
 * Receives the server's manifest offer and decides whether anything needs downloading.
 *
 * <p>The decision is one string comparison of SHA-256 root hashes. When they match, no
 * request is ever made to the download port.
 */
public final class ClientOfferHandler {

    /** Latest offer, kept so the UI/command layer can act on it later. */
    private static volatile PendingUpdate pending;

    public record PendingUpdate(String host, int port, Manifest manifest, String localRoot) {
        public int differingFiles() {
            return manifest.files().size();
        }
    }

    private ClientOfferHandler() {
    }

    public static PendingUpdate get() {
        return pending;
    }

    public static void clear() {
        pending = null;
    }

    public static void onOffer(SnapshotOfferPayload offer) {
        // A join-time offer is one of the moments the staged state can have moved on (the helper
        // for the previous session may have installed and deleted the plan). Once per join.
        DeferredApplier.refreshPendingCache();

        if (offer.isEmpty()) {
            // Server has nothing published, or the client was already current.
            pending = null;
            SnapshotSync.LOGGER.info("Server offered nothing new (or we are up to date)");
            return;
        }

        Manifest remote;
        try {
            remote = ManifestJson.fromBytes(offer.manifestJson());
        } catch (Exception e) {
            SnapshotSync.LOGGER.error("Malformed manifest from server", e);
            return;
        }

        // Defend against a server whose declared hash does not match its own manifest.
        if (!remote.rootHash().equals(offer.rootHash())) {
            SnapshotSync.LOGGER.warn("Server manifest hash mismatch: announced {}, computed {} - "
                            + "refusing the offer",
                    offer.rootHash(), remote.rootHash());
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        String host = resolveHost(mc);
        if (host.isBlank()) {
            SnapshotSync.LOGGER.warn("Cannot determine server host; ignoring offer");
            return;
        }

        String localRoot = LocalState.rootHash();
        String base = "http://" + host + ":" + offer.downloadPort();

        if (offer.rootHash().equals(localRoot)) {
            SnapshotSync.LOGGER.info("Client is up to date (root {})", localRoot);
            pending = null;
            notifyPlayer("snapsync.chat.up_to_date");
            return;
        }

        pending = new PendingUpdate(host, offer.downloadPort(), remote, localRoot);

        SnapshotSync.LOGGER.info("Update available: {} files, local root {} -> server root {}",
                remote.files().size(),
                localRoot.isEmpty() ? "<none>" : localRoot,
                offer.rootHash());

        if (Config.autoApplyOnJoin) {
            notifyPlayer("snapsync.chat.update_available_auto", remote.files().size(), base);
            SnapshotSyncer.startAsync();
        } else {
            notifyPlayer("snapsync.chat.update_available");
        }
    }

    static String resolveHost(Minecraft mc) {
        ServerData data = mc.getCurrentServer();
        if (data == null) {
            // Singleplayer / LAN host: we are the server, so talk to loopback.
            return mc.hasSingleplayerServer() ? "127.0.0.1" : "";
        }
        return stripPort(data.ip);
    }

    static String stripPort(String address) {
        if (address == null) {
            return "";
        }
        String a = address.trim();
        if (a.startsWith("[")) {
            int close = a.indexOf(']');
            return close > 0 ? a.substring(0, close + 1) : a;
        }
        int colon = a.lastIndexOf(':');
        if (colon > 0 && a.indexOf(':') == colon) {
            return a.substring(0, colon);
        }
        return a;
    }

    private static void notifyPlayer(String key, Object... args) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.translatable(key, args));
        }
    }
}
