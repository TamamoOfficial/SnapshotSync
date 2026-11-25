package com.snapsync.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** JSON (de)serialisation for {@link Manifest}, deliberately tiny and dependency-free. */
public final class ManifestJson {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson COMPACT = new Gson();

    private ManifestJson() {
    }

    public static byte[] toBytes(Manifest manifest) {
        JsonObject root = new JsonObject();
        root.addProperty("format", manifest.format());
        root.addProperty("snapshotId", manifest.snapshotId());
        root.addProperty("createdAt", manifest.createdAt());
        root.addProperty("rootHash", manifest.rootHash());

        JsonObject files = new JsonObject();
        manifest.files().forEach(files::addProperty);
        root.add("files", files);

        return COMPACT.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    public static Manifest fromBytes(byte[] data) {
        JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();

        int format = root.has("format") ? root.get("format").getAsInt() : Manifest.FORMAT;
        String id = root.has("snapshotId") ? root.get("snapshotId").getAsString() : "";
        long created = root.has("createdAt") ? root.get("createdAt").getAsLong() : 0L;

        Map<String, String> files = new HashMap<>();
        if (root.has("files")) {
            for (var e : root.getAsJsonObject("files").entrySet()) {
                files.put(e.getKey(), e.getValue().getAsString());
            }
        }

        return Manifest.of(format, id, created, files);
    }

    public static void write(Path path, Manifest manifest) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, toBytes(manifest));
    }

    public static Manifest read(Path path) throws IOException {
        return fromBytes(Files.readAllBytes(path));
    }
}
