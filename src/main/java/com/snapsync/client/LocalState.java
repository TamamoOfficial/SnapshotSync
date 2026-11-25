package com.snapsync.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.snapsync.SnapshotSync;
import com.snapsync.core.Manifest;
import com.snapsync.core.ManifestJson;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * What this client has already applied.
 *
 * <p>Storing the applied manifest (not just a hash) lets us verify individual files against
 * the expected hash before trusting them, so a locally edited config is detected and repaired
 * rather than silently kept.
 */
public final class LocalState {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LocalState() {
    }

    public static Path statePath() {
        return Path.of(SnapshotSync.STATE, "applied.json");
    }

    /** Root hash of what we last applied, or empty when nothing has been applied. */
    public static String rootHash() {
        Manifest m = load();
        return m == null ? "" : m.rootHash();
    }

    public static Manifest load() {
        Path path = statePath();
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return ManifestJson.read(path);
        } catch (Exception e) {
            SnapshotSync.LOGGER.warn("Could not read applied state at {}", path, e);
            return null;
        }
    }

    public static void save(Manifest manifest) throws IOException {
        Path path = statePath();
        Files.createDirectories(path.getParent());
        ManifestJson.write(path, manifest);
    }

    /** For diagnostics: per-path expected hash map, empty when unknown. */
    public static Map<String, String> expectedHashes() {
        Manifest m = load();
        return m == null ? new HashMap<>() : new HashMap<>(m.files());
    }
}
