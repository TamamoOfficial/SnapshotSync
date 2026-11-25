package com.snapsync.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** SHA-256 helpers. */
public final class Hashing {

    /** Read buffer size; kept modest because several hashes may run concurrently. */
    private static final int BUFFER = 64 * 1024;

    private Hashing() {
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JDK is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Lowercase hex digest of a file. */
    public static String sha256Hex(Path file) throws IOException {
        MessageDigest md = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(sha256().digest(data));
    }

    /** Hash a stream without buffering it in memory (used for download verification). */
    public static String sha256Hex(InputStream in) throws IOException {
        MessageDigest md = sha256();
        byte[] buf = new byte[BUFFER];
        int n;
        while ((n = in.read(buf)) != -1) {
            md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * Aggregate hash over a path->hash map.
     *
     * <p>Paths are sorted first, so the result does not depend on directory iteration order.
     * This is what the client compares on join: one string, no per-file round trips.
     */
    public static String rootHash(Map<String, String> files) {
        MessageDigest md = sha256();
        files.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                    md.update((byte) 0);
                    md.update(e.getValue().getBytes(StandardCharsets.UTF_8));
                    md.update((byte) '\n');
                });
        return HexFormat.of().formatHex(md.digest());
    }
}
