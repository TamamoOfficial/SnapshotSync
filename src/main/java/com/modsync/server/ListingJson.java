package com.modsync.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.modsync.ModSync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds the {@code /list.json} body: the jar file names available for download. */
public final class ListingJson {

    private static final Gson GSON = new GsonBuilder().create();

    private ListingJson() {
    }

    public static byte[] build(Path dir) {
        List<String> names = list(dir);
        return GSON.toJson(names).getBytes(StandardCharsets.UTF_8);
    }

    /** Number of downloadable jars, used for the handshake summary. */
    public static int count(Path dir) {
        return list(dir).size();
    }

    private static List<String> list(Path dir) {
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .forEach(names::add);
        } catch (IOException e) {
            ModSync.LOGGER.error("Could not list {}", dir, e);
        }
        return names;
    }
}
