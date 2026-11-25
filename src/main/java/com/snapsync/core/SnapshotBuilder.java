package com.snapsync.core;

import com.snapsync.Config;
import com.snapsync.SnapshotSync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Turns a set of source folders into a {@link Manifest} plus a content-addressed blob store.
 *
 * <p>Two levels of "don't repeat work":
 * <ul>
 *   <li>{@link HashCache} avoids re-hashing files whose size and mtime are unchanged.</li>
 *   <li>If the resulting root hash equals the already-published one, <b>nothing is written and
 *       the published snapshot is left alone</b>. Clients see no new snapshot id, so a timed
 *       build that finds no changes is completely free.</li>
 * </ul>
 *
 * <p>Publishing is atomic from a client's point of view: blobs are written first (immutable and
 * content-named, so a partial write is harmless), then the manifest pointer is swapped with a
 * single atomic move. A client therefore never sees a manifest referring to a blob that does
 * not exist yet.
 */
public final class SnapshotBuilder {

    /** One folder to include, and the prefix its files get inside the snapshot. */
    public record Source(String prefix, Path root) {
    }

    /**
     * Outcome of a build attempt.
     *
     * @param manifest the currently published manifest (new or pre-existing)
     * @param changed  what differs from the previous snapshot; empty when nothing was published
     * @param published true only when a new snapshot id was actually written
     */
    public record BuildResult(Manifest manifest, ChangeSummary changed, boolean published) {
        public boolean noChanges() {
            return !published;
        }
    }

    private SnapshotBuilder() {
    }

    public static Path repoDir() {
        return Path.of(SnapshotSync.REPO);
    }

    public static Path currentManifestPath() {
        return repoDir().resolve("current.json");
    }

    /** Blob store root, served as {@code /blobs/..} by the download server. */
    public static Path blobDir() {
        return repoDir().resolve("blobs");
    }

    /**
     * Repo-relative location of a blob, shared by the builder and the HTTP server so the two
     * can never disagree about the layout.
     */
    public static String blobRelative(String sha256) {
        return sha256.substring(0, 2) + "/" + sha256;
    }

    /**
     * Source folders from config, each used as its own prefix inside the snapshot.
     *
     * <p>The prefix is the folder path itself, so {@code includeFolders = ["mods","kubejs"]}
     * produces {@code mods/...} and {@code kubejs/...} in the manifest, and a custom folder
     * such as {@code "custom/stuff"} stays exactly where it was on the server.
     */
    public static List<Source> configuredSources() {
        List<Source> sources = new ArrayList<>();
        for (String folder : Config.includeFolders) {
            sources.add(new Source(folder, Path.of(folder)));
        }
        return sources;
    }

    /**
     * Builds and publishes a snapshot if - and only if - the content changed.
     *
     * <p>Does file hashing and must not run on the server thread.
     */
    public static BuildResult buildAndPublish() {
        List<Source> sources = configuredSources();
        if (sources.isEmpty()) {
            SnapshotSync.LOGGER.warn("No folders selected in config; nothing to snapshot");
            Manifest current = current();
            return new BuildResult(current, ChangeSummary.empty(), false);
        }

        Path repo = repoDir();
        Path blobs = blobDir();

        try {
            Files.createDirectories(blobs);

            HashCache cache = Config.rehashEveryBuild ? null : HashCache.load();

            Map<String, String> files = new LinkedHashMap<>();
            for (Source source : sources) {
                if (!Files.isDirectory(source.root())) {
                    SnapshotSync.LOGGER.info("Skipping {}: folder does not exist", source.prefix());
                    continue;
                }
                collect(source, blobs, files, cache);
            }

            if (files.isEmpty()) {
                SnapshotSync.LOGGER.warn("Snapshot would be empty; not publishing");
                return new BuildResult(current(), ChangeSummary.empty(), false);
            }

            // The single most important check: is anything actually different?
            Manifest previous = current();
            String newRoot = Hashing.rootHash(files);

            if (previous != null && previous.rootHash().equals(newRoot)) {
                if (cache != null) {
                    cache.retainOnly(files.keySet());
                    cache.save();
                }
                SnapshotSync.LOGGER.info(
                        "No content changes ({} files, {} hashed, {} cached); snapshot {} left as is",
                        files.size(), cache != null ? cache.misses() : files.size(),
                        cache != null ? cache.hits() : 0,
                        previous.snapshotId());
                return new BuildResult(previous, ChangeSummary.empty(), false);
            }

            ChangeSummary changes = diff(previous, files);

            String id = Long.toString(System.currentTimeMillis(), 36);
            Manifest manifest = new Manifest(id, System.currentTimeMillis(), files);

            Path staging = repo.resolve("staging-" + UUID.randomUUID());
            Files.createDirectories(staging);
            try {
                Path next = staging.resolve("manifest.json");
                ManifestJson.write(next, manifest);

                Files.createDirectories(repo);
                Files.move(next, currentManifestPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                deleteRecursively(staging);
            }

            if (cache != null) {
                cache.retainOnly(files.keySet());
                cache.save();
            }

            SnapshotSync.LOGGER.info("Published snapshot {} ({} files, {}): {}",
                    id, manifest.files().size(), changes.describe(), manifest.rootHash());

            return new BuildResult(manifest, changes, true);
        } catch (Exception e) {
            SnapshotSync.LOGGER.error("Failed to build snapshot", e);
            return new BuildResult(current(), ChangeSummary.empty(), false);
        }
    }

    /** Computes added / modified / removed relative to {@code previous}. */
    static ChangeSummary diff(Manifest previous, Map<String, String> files) {
        if (previous == null) {
            return new ChangeSummary(new TreeSet<>(files.keySet()), new TreeSet<>(), new TreeSet<>());
        }

        Map<String, String> old = previous.files();
        var added = new TreeSet<String>();
        var modified = new TreeSet<String>();
        var removed = new TreeSet<String>();

        for (var e : files.entrySet()) {
            String before = old.get(e.getKey());
            if (before == null) {
                added.add(e.getKey());
            } else if (!before.equals(e.getValue())) {
                modified.add(e.getKey());
            }
        }

        for (String key : old.keySet()) {
            if (!files.containsKey(key)) {
                removed.add(key);
            }
        }

        return new ChangeSummary(added, modified, removed);
    }

    private static void collect(Source source, Path blobs, Map<String, String> files, HashCache cache)
            throws IOException {
        try (Stream<Path> walk = Files.walk(source.root(), 12)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String relative = source.root().relativize(file).toString().replace('\\', '/');
                String path = source.prefix() + "/" + relative;

                // Explicit opt-out only: a wrongly filtered jar would leave every client
                // without a required mod, so nothing is guessed from jar contents.
                if (Config.excludeMods.contains(file.getFileName().toString()
                        .toLowerCase(java.util.Locale.ROOT))) {
                    SnapshotSync.LOGGER.info("Excluded by excludeMods: {}", path);
                    continue;
                }

                String sha;
                try {
                    sha = cache != null ? cache.hashOf(path, file) : Hashing.sha256Hex(file);
                } catch (IOException e) {
                    SnapshotSync.LOGGER.warn("Skipping unreadable file {}", file, e);
                    continue;
                }

                Path blob = blobs.resolve(blobRelative(sha));
                if (!Files.isRegularFile(blob)) {
                    // Copy beside the target and rename: a half-written blob must never be
                    // visible under its content hash.
                    Files.createDirectories(blob.getParent());
                    Path tmp = blob.resolveSibling(sha + ".tmp");
                    try (InputStream in = Files.newInputStream(file)) {
                        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.move(tmp, blob, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                }

                files.put(path, sha);
            }
        }
    }

    /** Loads the currently published manifest, or {@code null} when none exists yet. */
    public static Manifest current() {
        Path path = currentManifestPath();
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return ManifestJson.read(path);
        } catch (Exception e) {
            SnapshotSync.LOGGER.warn("Failed to read {}", path, e);
            return null;
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
