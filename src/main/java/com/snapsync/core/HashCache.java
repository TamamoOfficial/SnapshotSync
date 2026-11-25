package com.snapsync.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.snapsync.SnapshotSync;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Remembers {@code path -> (size, mtime, sha256)} so unchanged files are not re-hashed.
 *
 * <p>Without this, every timed build re-reads every jar in {@code mods}. On a 3 GB pack that
 * is minutes of disk I/O per cycle, and the result is almost always identical - which is
 * exactly the waste the "only package what changed" requirement is about.
 *
 * <p><b>Correctness tradeoff.</b> An entry is reused only when both size and modification time
 * match exactly (nanosecond-resolution where the filesystem provides it). The only way to fool
 * it is to rewrite a file with <i>identical length</i> inside the same filesystem timestamp
 * tick. That is why {@code rehashEveryBuild} exists: set it to true to disable the shortcut and
 * hash everything, at the cost of speed.
 */
public final class HashCache {

    private static final Gson GSON = new GsonBuilder().create();

    /** Cached facts about one file. */
    public record Entry(long size, long mtimeNanos, String sha256) {
    }

    private final Path file;
    private final Map<String, Entry> entries = new HashMap<>();
    private int hits;
    private int misses;

    private HashCache(Path file) {
        this.file = file;
    }

    public static Path cachePath() {
        return Path.of(SnapshotSync.STATE, "hash-cache.json");
    }

    public static HashCache load() {
        return load(cachePath());
    }

    public static HashCache load(Path file) {
        HashCache cache = new HashCache(file);
        if (!Files.isRegularFile(file)) {
            return cache;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, Entry> loaded = GSON.fromJson(reader,
                    new TypeToken<Map<String, Entry>>() { }.getType());
            if (loaded != null) {
                cache.entries.putAll(loaded);
            }
        } catch (Exception e) {
            // A corrupt cache is not a problem: fall back to hashing everything.
            SnapshotSync.LOGGER.warn("Ignoring unreadable hash cache at {}", file, e);
        }
        return cache;
    }

    /**
     * Returns the cached hash when the file's size and mtime still match, otherwise hashes it
     * and records the new facts.
     */
    public String hashOf(String key, Path file) throws IOException {
        long size = Files.size(file);
        long mtime = mtimeNanos(file);

        Entry cached = entries.get(key);
        if (cached != null && cached.size() == size && cached.mtimeNanos() == mtime) {
            // Guard against the one case the cache cannot see: a rewrite that preserved both
            // length and timestamp. A file modified within the last few seconds is exactly the
            // kind that an editor or installer is likely to still be touching, so re-hash those
            // rather than trusting a stale entry.
            if (System.currentTimeMillis() - (mtime / 1_000_000L) > RECENT_WRITE_WINDOW_MS) {
                hits++;
                return cached.sha256();
            }
        }

        misses++;
        String sha = Hashing.sha256Hex(file);
        entries.put(key, new Entry(size, mtime, sha));
        return sha;
    }

    /** Files touched this recently are always re-hashed, see {@link #hashOf}. */
    private static final long RECENT_WRITE_WINDOW_MS = 10_000L;

    /** Drops entries for paths that no longer exist in the snapshot. */
    public void retainOnly(java.util.Set<String> keys) {
        entries.keySet().retainAll(keys);
    }

    public int hits() {
        return hits;
    }

    public int misses() {
        return misses;
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(entries, writer);
            }
        } catch (IOException e) {
            SnapshotSync.LOGGER.warn("Could not persist hash cache to {}", file, e);
        }
    }

    private static long mtimeNanos(Path file) throws IOException {
        FileTime time = Files.getLastModifiedTime(file);
        return time.to(java.util.concurrent.TimeUnit.NANOSECONDS);
    }
}
