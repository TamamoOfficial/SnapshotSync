package com.snapsync.client;

import com.snapsync.Config;
import com.snapsync.SnapshotSync;
import com.snapsync.core.Hashing;
import com.snapsync.core.Manifest;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Applies a snapshot: compares local files against the manifest, downloads only what differs,
 * then installs everything atomically per file.
 *
 * <p>Two levels of parallelism, for two different bottlenecks:
 * <ul>
 *   <li>Blob downloads use {@link ParallelDownloader} (ranged, multi-connection).</li>
 *   <li>Independent blobs are fetched concurrently too, so many small files do not serialise.</li>
 * </ul>
 */
public final class SnapshotSyncer {

    /**
     * Absolute locations a sync must never write into, whatever the server packs.
     *
     * <p>This is deliberately a short list of things that are <i>not</i> sync payload: the
     * mod's own state, and the JVM/tooling directories that mods trip over. Every real
     * game-content folder is allowed - including {@code shaderpacks}, {@code schematics},
     * {@code patchouli_books}, {@code itemscroller} and anything a new mod invents - because
     * the whole point is that the server decides what to distribute.
     *
     * <p>Note what is <b>not</b> here any more: {@code saves} was removed because PvP/minigame
     * servers genuinely synchronise adventure maps, and refusing it silently broke those
     * setups. It stays out of the default {@code includeFolders} so nobody gets world data
     * pushed by accident, but an admin who asks for it gets it.
     */
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "snapsync/"   // our own repo/cache/state - self-clobbering would corrupt the sync
    );

    private static volatile boolean running;

    private SnapshotSyncer() {
    }

    public static boolean isRunning() {
        return running;
    }

    public static void startAsync() {
        startAsync(ClientOfferHandler.get());
    }

    /**
     * Starts a sync for an explicitly supplied update, with no Minecraft connection involved.
     *
     * <p>This is the pre-join path: the player pressed Update on the multiplayer screen, the
     * manifest was fetched over plain HTTP, and there is no offer packet anywhere. Everything
     * after this point is shared with the in-game path.
     */
    public static void startAsync(ClientOfferHandler.PendingUpdate pending) {
        if (running) {
            SnapshotSync.LOGGER.info("Sync already in progress");
            return;
        }

        if (pending == null) {
            message("snapsync.chat.nothing_to_sync");
            return;
        }

        running = true;

        Thread worker = new Thread(() -> {
            try {
                sync(pending);
            } catch (Throwable t) {
                SnapshotSync.LOGGER.error("Sync failed", t);
                message("snapsync.chat.sync_failed");
            } finally {
                running = false;
            }
        }, "SnapshotSync-Sync");
        worker.setDaemon(true);
        worker.start();
    }

    private static void sync(ClientOfferHandler.PendingUpdate pending) throws Exception {
        Manifest manifest = pending.manifest();
        Path gameDir = gameDir();
        String base = "http://" + pending.host() + ":" + pending.port();

        // --- 1. work out what is missing or different -------------------------
        Map<String, String> local = LocalState.expectedHashes();
        Map<String, String> blobsToFetch = new ConcurrentHashMap<>();
        List<Map.Entry<String, String>> toInstall = new ArrayList<>();

        for (Map.Entry<String, String> e : manifest.files().entrySet()) {
            String path = e.getKey();
            String want = e.getValue();

            if (!isAllowed(path)) {
                SnapshotSync.LOGGER.warn("Manifest entry rejected by client policy: {}", path);
                continue;
            }

            Path target = gameDir.resolve(path).normalize();
            if (!target.startsWith(gameDir.normalize())) {
                SnapshotSync.LOGGER.warn("Refusing path escape: {}", path);
                continue;
            }

            toInstall.add(e);

            // Trust the recorded state only if the file still matches what we wrote.
            boolean unchanged = want.equals(local.get(path))
                    && Files.isRegularFile(target)
                    && want.equals(Hashing.sha256Hex(target));

            if (!unchanged) {
                blobsToFetch.putIfAbsent(want, path);
            }
        }

        SnapshotSync.LOGGER.info("Sync: {} entries, {} unique blob(s) to download",
                toInstall.size(), blobsToFetch.size());

        // --- 2. download blobs in parallel ------------------------------------
        Path blobCache = Path.of(SnapshotSync.CACHE, "blobs");
        Files.createDirectories(blobCache);

        if (!blobsToFetch.isEmpty()) {
            fetchBlobs(base, blobsToFetch, blobCache);
        }

        // --- 3. install -------------------------------------------------------
        // Jars cannot be replaced while the game runs (Windows keeps them open), so they are
        // staged and installed by a helper script after the process exits. Everything else is
        // an ordinary file and is written immediately.
        int installed = 0;
        Map<String, Path> deferred = new LinkedHashMap<>();

        for (Map.Entry<String, String> e : toInstall) {
            String path = e.getKey();
            String sha = e.getValue();

            Path cached = blobCache.resolve(sha);
            Path target = gameDir.resolve(path).normalize();

            if (Files.isRegularFile(target) && sha.equals(Hashing.sha256Hex(target))) {
                continue;
            }

            if (!Files.isRegularFile(cached)) {
                SnapshotSync.LOGGER.warn("Blob {} missing from cache; skipping {}", sha, path);
                continue;
            }

            if (DeferredApplier.needsDeferral(path)) {
                deferred.put(path, cached);
                continue;
            }

            Files.createDirectories(target.getParent());
            Files.copy(cached, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            installed++;
        }

        int staged = 0;
        if (!deferred.isEmpty()) {
            staged = DeferredApplier.stage(deferred, gameDir);
        }

        // --- 4. record what we applied ---------------------------------------
        LocalState.save(manifest);

        SnapshotSync.LOGGER.info(
                "Sync complete: {} file(s) installed now, {} jar(s) staged for install after exit, root now {}",
                installed, staged, manifest.rootHash());

        if (staged > 0) {
            message("snapsync.chat.applied_now", installed, staged);
            message("snapsync.chat.quit_normally");
        } else if (installed > 0) {
            message("snapsync.chat.applied_only", installed);
        } else {
            message("snapsync.chat.up_to_date");
        }
    }

    private static void fetchBlobs(String base, Map<String, String> blobs, Path cache) throws Exception {
        int threads = Math.min(Math.max(1, Config.maxParallelConnections), blobs.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "SnapshotSync-Blob");
            t.setDaemon(true);
            return t;
        });

        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (Map.Entry<String, String> e : blobs.entrySet()) {
                String sha = e.getKey();
                Path out = cache.resolve(sha);

                if (Files.isRegularFile(out) && sha.equals(Hashing.sha256Hex(out))) {
                    ok.incrementAndGet();
                    continue;
                }

                futures.add(pool.submit(() -> {
                    ParallelDownloader downloader = new ParallelDownloader(base);
                    downloader.fetch(sha, -1, out);
                    ok.incrementAndGet();
                    return null;
                }));
            }

            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        SnapshotSync.LOGGER.info("Fetched {}/{} blob(s)", ok.get(), blobs.size());
    }

    /**
     * Client-side write policy: a relative path that does not try to leave the game directory
     * and is not in a forbidden area.
     *
     * <p>Path traversal is checked here as well as on the server, because the manifest comes
     * over the network and must not be trusted.
     */
    private static boolean isAllowed(String path) {
        if (path.isEmpty() || path.startsWith("/") || path.contains(":") || path.contains("\\")) {
            return false;
        }

        for (String part : path.split("/")) {
            if ("..".equals(part) || ".".equals(part)) {
                return false;
            }
        }

        String lower = path.toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : FORBIDDEN_PREFIXES) {
            if (lower.startsWith(forbidden)) {
                return false;
            }
        }
        return true;
    }

    static Path gameDir() {
        return Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
    }

    /** Sends a localised chat line; the key is resolved on the client. */
    private static void message(String key, Object... args) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.translatable(key, args));
        }
    }
}
