package com.snapsync.core;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * One published snapshot: the mapping from relative path to SHA-256 of its content.
 *
 * <p>Two things follow from storing content hashes rather than a single archive hash:
 * <ul>
 *   <li>The client can tell <i>exactly</i> which files differ, so a sync is incremental.</li>
 *   <li>The same bytes under different names are fetched once (blobs are content-addressed).</li>
 * </ul>
 */
public final class Manifest {

    /** The wire/json format version, bumped when the shape changes. */
    public static final int FORMAT = 1;

    private final int format;
    private final String snapshotId;
    private final long createdAt;
    private final TreeMap<String, String> files;
    /** Aggregate hash over the sorted (path, hash) pairs; the fast "is anything different" check. */
    private final String rootHash;

    public Manifest(String snapshotId, long createdAt, Map<String, String> files) {
        this(FORMAT, snapshotId, createdAt, files, null);
    }

    private Manifest(int format, String snapshotId, long createdAt,
                     Map<String, String> files, String rootHash) {
        this.format = format;
        this.snapshotId = snapshotId;
        this.createdAt = createdAt;
        this.files = new TreeMap<>(files);
        this.rootHash = rootHash != null ? rootHash : Hashing.rootHash(this.files);
    }

    /** Rebuild from parsed json, recomputing the root hash defensively. */
    public static Manifest of(int format, String snapshotId, long createdAt, Map<String, String> files) {
        return new Manifest(format, snapshotId, createdAt, files, null);
    }

    public int format() {
        return format;
    }

    public String snapshotId() {
        return snapshotId;
    }

    public long createdAt() {
        return createdAt;
    }

    public Map<String, String> files() {
        return Collections.unmodifiableMap(files);
    }

    public String rootHash() {
        return rootHash;
    }

    /** Sha-256 of a single file's content, or empty when the snapshot does not contain it. */
    public String hashOf(String path) {
        return files.get(path);
    }

    /** Content-addressed blob key, so identical files are downloaded once. */
    public static String blobKey(String sha256) {
        return sha256;
    }

    /** Repo-relative path of a blob (delegates so there is a single source of truth). */
    public static String blobPath(String sha256) {
        return "blobs/" + SnapshotBuilder.blobRelative(sha256);
    }

    /**
     * Number of files this snapshot has in common with {@code other} positionally, used only
     * for a human-readable summary in the logs.
     */
    public int countDifferent(Manifest other) {
        int n = 0;
        for (var e : files.entrySet()) {
            if (!e.getValue().equals(other.files.get(e.getKey()))) {
                n++;
            }
        }
        for (String k : other.files.keySet()) {
            if (!files.containsKey(k)) {
                n++;
            }
        }
        return n;
    }

    Manifest withId(String id) {
        return new Manifest(format, id, createdAt, files, rootHash);
    }
}
