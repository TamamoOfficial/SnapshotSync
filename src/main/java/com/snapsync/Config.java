package com.snapsync;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings. Values live in plain static fields and are only written from
 * {@link ModConfigEvent}, so nothing caches them in {@code static final}.
 */
@EventBusSubscriber(modid = SnapshotSync.MODID, bus = EventBusSubscriber.Bus.MOD)
public class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ----------------------------------------------------------------- transport

    private static final ModConfigSpec.IntValue DOWNLOAD_PORT_OFFSET = BUILDER
            .comment(
                    "The download listener binds to (Minecraft server port + this offset).",
                    "Offset 0 is impossible: Minecraft already holds the TCP port it listens on,",
                    "so a second listener on the same number would fail to bind.",
                    "Default: 1"
            )
            .defineInRange("downloadPortOffset", 1, 1, 1000);

    private static final ModConfigSpec.IntValue FIXED_DOWNLOAD_PORT = BUILDER
            .comment(
                    "If > 0, use this absolute port instead of deriving it from the server port.",
                    "Useful behind NAT where only one port is forwarded. Default: 0 (derive)"
            )
            .defineInRange("fixedDownloadPort", 0, 0, 65535);

    private static final ModConfigSpec.IntValue MAX_PARALLEL_CONNECTIONS = BUILDER
            .comment("Parallel TCP connections per download. Default: 4")
            .defineInRange("maxParallelConnections", 4, 1, 16);

    private static final ModConfigSpec.IntValue CHUNK_SIZE_KB = BUILDER
            .comment(
                    "Granularity of ranged requests, in KiB. Smaller means better balancing across",
                    "uneven connections; larger means fewer request round-trips. Default: 512"
            )
            .defineInRange("chunkSizeKb", 512, 16, 16384);

    // -------------------------------------------------------------------- idle

    // ------------------------------------------------------------------ mode

    private static final ModConfigSpec.EnumValue<PackMode> PACK_MODE = BUILDER
            .comment(
                    "When to build a snapshot. Switching this in the config file is all that is",
                    "needed to change modes; no other setting has to be touched.",
                    "",
                    "Allowed values (case-insensitive): IDLE | INTERVAL",
                    "A typo here fails config loading, so copy one of the two names exactly.",
                    "",
                    "IDLE     = one-shot latch. Builds at the first moment the server is idle and",
                    "           healthy after being armed, then waits for the next arming",
                    "           (server start, /reload, LAN publish, /snapsync rebuild).",
                    "           Tuning: idleDelaySeconds, minTpsForSnapshot.",
                    "INTERVAL = fixed timer. Builds every intervalMinutes, regardless of players",
                    "           or TPS. Overlapping builds are still prevented.",
                    "           Tuning: intervalMinutes, buildOnStart.",
                    "",
                    "Default: IDLE"
            )
            .defineEnum("packMode", PackMode.IDLE);

    private static final ModConfigSpec.IntValue INTERVAL_MINUTES = BUILDER
            .comment(
                    "INTERVAL mode only: how many minutes between snapshots.",
                    "The first build happens one interval after the server starts.",
                    "Default: 30"
            )
            .defineInRange("intervalMinutes", 30, 1, 10080);

    private static final ModConfigSpec.BooleanValue BUILD_ON_START = BUILDER
            .comment(
                    "INTERVAL mode only: also build once shortly after startup, instead of",
                    "waiting a full interval for the first snapshot.",
                    "Default: true"
            )
            .define("buildOnStart", true);

    private static final ModConfigSpec.ConfigValue<List<? extends String>> EXCLUDE_MODS = BUILDER
            .comment(
                    "Jar file names in mods/ that must NOT be published to clients.",
                    "",
                    "Example:",
                    "  excludeMods = [\"lithium.jar\", \"servercore-1.2.3.jar\"]",
                    "",
                    "Why an explicit list instead of auto-detection:",
                    "  Whether a mod is safe to send depends on the deployment, not on the jar.",
                    "  A dedicated server usually runs mods that are all required by clients too;",
                    "  server-only optimisation mods are the exception. Detecting that from a jar",
                    "  is unreliable - ordinary side-checking code looks identical to a side",
                    "  restriction - and a wrong guess makes every client unable to join.",
                    "  So nothing is filtered automatically. You name what to hold back.",
                    "",
                    "Default: [] (publish every jar in mods/)"
            )
            .defineListAllowEmpty("excludeMods", List.of(), () -> "example.jar",
                    o -> o instanceof String s2 && !s2.isBlank());

    private static final ModConfigSpec.BooleanValue REHASH_EVERY_BUILD = BUILDER
            .comment(
                    "Hash every file on every build instead of trusting size+mtime.",
                    "",
                    "false = default. Unchanged files (same size and mtime) reuse their cached",
                    "        SHA-256, so a periodic build on a large pack costs almost nothing.",
                    "true  = always re-read every file. Slower, but immune to the one edge case",
                    "        where a file is rewritten at identical length within the same",
                    "        filesystem timestamp tick.",
                    "",
                    "Default: false"
            )
            .define("rehashEveryBuild", false);

    private static final ModConfigSpec.IntValue IDLE_DELAY_SECONDS = BUILDER
            .comment(
                    "How long the server must stay empty before a snapshot is built.",
                    "Default: 60"
            )
            .defineInRange("idleDelaySeconds", 60, 0, 3600);

    private static final ModConfigSpec.DoubleValue MIN_TPS_FOR_SNAPSHOT = BUILDER
            .comment(
                    "A snapshot is only built while the server is at least this healthy.",
                    "TPS is derived from the smoothed tick time (20 / max(1, mspt/50)).",
                    "Default: 19.0"
            )
            .defineInRange("minTpsForSnapshot", 19.0, 1.0, 20.0);

    private static final ModConfigSpec.IntValue SNAPSHOT_COOLDOWN_SECONDS = BUILDER
            .comment(
                    "Minimum time between two automatic snapshots. Default: 300"
            )
            .defineInRange("snapshotCooldownSeconds", 300, 0, 86400);

    // ---------------------------------------------------------------- contents

    private static final ModConfigSpec.ConfigValue<List<? extends String>> INCLUDE_FOLDERS = BUILDER
            .comment(
                    "Which folders to pack. Paths are relative to the game/server directory.",
                    "",
                    "Any folder in the game directory may be listed, not just the defaults.",
                    "Typical extra examples:",
                    "  patchouli_books   scg   shaderpacks   schematics   itemscroller",
                    "  customnpcs        TConstruct   journeyMap    fancymenu",
                    "",
                    "Example:",
                    "  includeFolders = [\"config\", \"mods\", \"kubejs\", \"patchouli_books\", \"schematics\"]",
                    "",
                    "Nested paths work too (e.g. \"kubejs/server_scripts\").",
                    "",
                    "Rules:",
                    "  - absolute paths (C:\\\\..., /home/...) are rejected",
                    "  - any path containing '..' is rejected (no escaping the game directory)",
                    "  - 'snapsync' itself is rejected (it holds this mod's own files)",
                    "  - a missing folder is skipped with a log line, not an error",
                    "  - an empty list means \"nothing to pack\" and no snapshot is published",
                    "",
                    "Note: the client never deletes files it did not receive, so removing a folder",
                    "from this list stops syncing it but does not erase it on other machines.",
                    "",
                    "Default: [\"mods\", \"config\", \"kubejs\", \"resourcepacks\"]"
            )
            .defineListAllowEmpty("includeFolders",
                    List.of("mods", "config", "kubejs", "resourcepacks"),
                    () -> "mods",
                    o -> o instanceof String s && !s.isBlank());

    private static final ModConfigSpec.BooleanValue AUTO_APPLY_ON_JOIN = BUILDER
            .comment(
                    "If true, the client starts syncing as soon as it learns the manifest differs.",
                    "If false, the player is told an update exists and must run /snapsync pull.",
                    "Default: false"
            )
            .define("autoApplyOnJoin", false);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static int downloadPortOffset = 1;
    public static int fixedDownloadPort = 0;
    public static int maxParallelConnections = 4;
    public static int chunkSizeKb = 512;

    public static PackMode packMode = PackMode.IDLE;
    public static int intervalMinutes = 30;
    public static boolean buildOnStart = true;

    public static int idleDelaySeconds = 60;
    public static double minTpsForSnapshot = 19.0;
    public static int snapshotCooldownSeconds = 300;

    public static boolean rehashEveryBuild = false;
    public static List<String> excludeMods = List.of();

    public static List<String> includeFolders =
            List.of("mods", "config", "kubejs", "resourcepacks");

    public static boolean autoApplyOnJoin = false;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (!event.getConfig().getSpec().equals(SPEC)) {
            return;
        }

        downloadPortOffset = DOWNLOAD_PORT_OFFSET.get();
        fixedDownloadPort = FIXED_DOWNLOAD_PORT.get();
        maxParallelConnections = MAX_PARALLEL_CONNECTIONS.get();
        chunkSizeKb = CHUNK_SIZE_KB.get();

        packMode = PACK_MODE.get();
        intervalMinutes = INTERVAL_MINUTES.get();
        buildOnStart = BUILD_ON_START.get();

        idleDelaySeconds = IDLE_DELAY_SECONDS.get();
        minTpsForSnapshot = MIN_TPS_FOR_SNAPSHOT.get();
        snapshotCooldownSeconds = SNAPSHOT_COOLDOWN_SECONDS.get();
        rehashEveryBuild = REHASH_EVERY_BUILD.get();
        excludeMods = EXCLUDE_MODS.get().stream()
                .filter(o -> o instanceof String)
                .map(o -> ((String) o).trim().toLowerCase(java.util.Locale.ROOT))
                .filter(s2 -> !s2.isEmpty())
                .distinct()
                .toList();

        includeFolders = sanitizeFolders(INCLUDE_FOLDERS.get());

        autoApplyOnJoin = AUTO_APPLY_ON_JOIN.get();

        SnapshotSync.LOGGER.info(
                "SnapshotSync config: mode={}, offset={}, fixedPort={}, parallel={}, chunk={}KiB",
                packMode, downloadPortOffset, fixedDownloadPort, maxParallelConnections, chunkSizeKb);

        if (packMode == PackMode.INTERVAL) {
            SnapshotSync.LOGGER.info(
                    "  INTERVAL mode: every {} minute(s), buildOnStart={}", intervalMinutes, buildOnStart);
        } else {
            SnapshotSync.LOGGER.info(
                    "  IDLE mode: idle={}s, minTps={}, cooldown={}s",
                    idleDelaySeconds, minTpsForSnapshot, snapshotCooldownSeconds);
        }
    }

    /**
     * Normalises the configured folder list and drops unsafe entries.
     *
     * <p>This is the security boundary for "any folder you like": the client resolves these
     * names against its own game directory, so an absolute path or a {@code ..} segment would
     * let a server write anywhere on a player's disk. Both are rejected here, once, rather than
     * checked at every use site.
     */
    static List<String> sanitizeFolders(List<? extends String> raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }

        for (String entry : raw) {
            if (entry == null) {
                continue;
            }

            String value = entry.trim().replace('\\', '/');

            while (value.startsWith("./")) {
                value = value.substring(2);
            }
            while (value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }

            if (value.isEmpty()) {
                continue;
            }

            if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
                SnapshotSync.LOGGER.warn("includeFolders: rejecting absolute path '{}'", entry);
                continue;
            }

            boolean escapes = false;
            for (String part : value.split("/")) {
                if ("..".equals(part)) {
                    escapes = true;
                    break;
                }
            }
            if (escapes) {
                SnapshotSync.LOGGER.warn("includeFolders: rejecting path with '..': '{}'", entry);
                continue;
            }

            if (!result.contains(value)) {
                // Packing our own working directory would make each build include the previous
                // build's output, so the snapshot would grow without bound.
                if (value.equals(SnapshotSync.DIR)
                        || value.startsWith(SnapshotSync.DIR + "/")) {
                    SnapshotSync.LOGGER.warn(
                            "includeFolders: skipping '{}' - it contains SnapshotSync's own files",
                            value);
                    continue;
                }
                result.add(value);
            }
        }

        return result;
    }

    /**
     * Resolves the download port from the Minecraft server port.
     *
     * <p>Deriving instead of hardcoding is what makes this work for both a dedicated server
     * and a LAN world: the LAN host's ephemeral port differs on every launch, so any fixed
     * value would break.
     */
    public static int resolveDownloadPort(int minecraftPort) {
        if (fixedDownloadPort > 0) {
            return fixedDownloadPort;
        }
        int derived = minecraftPort + downloadPortOffset;
        return derived > 65535 ? minecraftPort - downloadPortOffset : derived;
    }
}
