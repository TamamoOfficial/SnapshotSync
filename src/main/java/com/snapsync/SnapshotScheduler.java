package com.snapsync;

import com.snapsync.server.DownloadServer;
import com.snapsync.core.Manifest;
import com.snapsync.core.SnapshotBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decides <b>when</b> a snapshot may be built, and when the download listener may exist.
 *
 * <h2>Two modes, selected by {@code packMode} in the config</h2>
 *
 * <b>{@link PackMode#IDLE}</b> - one-shot latch per arming:
 * <ol>
 *   <li>Armed on server start, and re-armed on {@code /reload}.</li>
 *   <li>While armed, waits for the <i>first</i> moment the server is idle and healthy.</li>
 *   <li>Builds exactly one snapshot, then disarms until something re-arms it.</li>
 * </ol>
 * A long-running server therefore does not repack every time the last player leaves, and an
 * admin has an explicit {@code /reload} (or {@code /snapsync rebuild}) to ask for a refresh.
 *
 * <b>{@link PackMode#INTERVAL}</b> - plain wall clock:
 * <ol>
 *   <li>Records the last build time; once {@code intervalMinutes} have passed, builds.</li>
 *   <li>Ignores the idle gate and the TPS gate entirely, so "every N minutes" really means
 *       every N minutes. Both gates exist to protect the server from avoidable work; a timer
 *       that silently skips intervals is worse than one that is a little late.</li>
 *   <li>Still serialises builds, so a long pack cannot pile up.</li>
 * </ol>
 *
 * <h2>LAN hosts need a different idle definition</h2>
 * On a LAN host the integrating player is permanently connected, so {@code playerCount()==0}
 * can never hold. There, "idle" means "nobody but the host is connected", and only after the
 * world has actually been shared to LAN. Publishing is also what tells us the real port: before
 * {@code publishServer} runs, {@code IntegratedServer.getPort()} returns 0.
 */
public final class SnapshotScheduler {

    /** Evaluate once per second. */
    private static final int CHECK_INTERVAL_TICKS = 20;

    /** Never build below this, whatever the config says. */
    private static final double HARD_FLOOR_TPS = 15.0;

    private static final ExecutorService BUILDER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SnapshotSync-Builder");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicBoolean BUILDING = new AtomicBoolean(false);

    private static volatile boolean armed;
    private static volatile String armedReason = "";

    /** Set once the download listener is bound for this world/session. */
    private static volatile boolean serverStarted;

    private static int tickCounter;
    private static long idleSinceMillis;
    private static long lastBuildMillis;

    private SnapshotScheduler() {
    }

    // ------------------------------------------------------------------ arming

    /** Arm (or re-arm) so the next idle window produces a snapshot. IDLE mode only. */
    public static void arm(String reason) {
        if (Config.packMode == PackMode.INTERVAL) {
            // Arming is meaningless on a timer: the clock is the trigger. A /reload should
            // still cause a refresh, so treat it as "build on the next evaluation".
            if ("reload".equals(reason) || "LAN published".equals(reason)) {
                // Bring the next build forward without disturbing the configured cadence.
                lastBuildMillis = System.currentTimeMillis()
                        - Math.max(1L, Config.intervalMinutes * 60_000L);
                SnapshotSync.LOGGER.info("Reload/publish in INTERVAL mode; rebuilding at next tick");
            }
            return;
        }

        if (BUILDING.get()) {
            // Re-arming mid-build would queue a second pack immediately afterwards; instead
            // let the running build finish and mark that another one is owed.
            rearmAfterBuild = true;
            return;
        }

        armed = true;
        armedReason = reason;
        idleSinceMillis = 0L;
        SnapshotSync.LOGGER.info("Snapshot armed ({}); will build at the next idle moment", reason);
    }

    private static volatile boolean rearmAfterBuild;

    /** INTERVAL mode: true when the startup snapshot still has to be built. */
    private static volatile boolean buildOnStartPending;

    /** Result of the most recent build, for {@code /snapsync status}. */
    private static volatile com.snapsync.core.ChangeSummary lastChangeSummary =
            com.snapsync.core.ChangeSummary.empty();
    private static volatile boolean lastBuildPublished;

    public static void disarm() {
        armed = false;
    }

    public static boolean isArmed() {
        return armed;
    }

    public static String armedReason() {
        return armedReason;
    }

    // --------------------------------------------------------------- lifecycle

    public static void onServerStarted(MinecraftServer server) {
        serverStarted = true;
        idleSinceMillis = 0L;

        // Dedicated servers have their port immediately; LAN hosts only after publishing,
        // which is handled in onTick via ensureDownloadServer().
        if (server.getPort() > 0) {
            ensureDownloadServer(server.getPort());
        } else {
            SnapshotSync.LOGGER.info("No server port yet (singleplayer/LAN); "
                    + "download listener will bind once the world is shared");
        }

        if (Config.packMode == PackMode.INTERVAL) {
            // The clock starts at server start. buildOnStart means "build once right away",
            // tracked by an explicit flag rather than by a sentinel timestamp, because 0 is
            // also used to mean "nothing built yet".
            lastBuildMillis = System.currentTimeMillis();
            buildOnStartPending = Config.buildOnStart;
            SnapshotSync.LOGGER.info("INTERVAL mode: every {} minute(s), buildOnStart={}",
                    Config.intervalMinutes, Config.buildOnStart);
        } else {
            arm("server started");
        }
    }

    public static void onServerStopping() {
        disarm();
        serverStarted = false;
        DownloadServer.stopInstance();
    }

    /** Called every tick; work is gated to once per second. */
    public static void onTick(MinecraftServer server) {
        // The download port can appear later than server start (LAN publishing).
        if (serverStarted && DownloadServer.instance() == null) {
            int port = server.getPort();
            if (port > 0) {
                if (ensureDownloadServer(port)) {
                    // Publishing the world is also the moment a LAN snapshot becomes useful.
                    arm("LAN published");
                }
            }
        }

        if (++tickCounter < CHECK_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        if (BUILDING.get()) {
            return;
        }

        if (Config.packMode == PackMode.INTERVAL) {
            evaluateInterval();
            return;
        }

        if (!armed) {
            return;
        }

        evaluateIdle(server);
    }

    private static boolean ensureDownloadServer(int mcPort) {
        if (DownloadServer.instance() != null) {
            return false;
        }
        int downloadPort = Config.resolveDownloadPort(mcPort);
        SnapshotSync.LOGGER.info("Binding download listener: server port {} -> {}",
                mcPort, downloadPort);
        return DownloadServer.start(downloadPort) != null;
    }

    // ------------------------------------------------------------- evaluation

    /**
     * INTERVAL mode: build purely on elapsed wall-clock time.
     *
     * <p>Deliberately does not consult {@link #isIdleEnough} or {@link #tps}. Those gates exist
     * to avoid unnecessary work in IDLE mode; applying them here would turn "every N minutes"
     * into "every N minutes, but only if the server happens to be quiet and healthy", which is
     * not what a fixed schedule means.
     */
    private static void evaluateInterval() {
        long now = System.currentTimeMillis();

        if (buildOnStartPending) {
            buildOnStartPending = false;
            SnapshotSync.LOGGER.info("INTERVAL mode: building initial snapshot at startup");
            submitBuild();
            return;
        }

        long intervalMillis = Config.intervalMinutes * 60_000L;
        if (now - lastBuildMillis < intervalMillis) {
            return;
        }

        SnapshotSync.LOGGER.info("INTERVAL mode: {} minute(s) elapsed, building snapshot",
                Config.intervalMinutes);
        submitBuild();
    }

    /** IDLE mode: build at the first idle + healthy moment after being armed. */
    private static void evaluateIdle(MinecraftServer server) {
        long now = System.currentTimeMillis();

        if (!isIdleEnough(server)) {
            idleSinceMillis = 0L;
            return;
        }

        if (idleSinceMillis == 0L) {
            idleSinceMillis = now;
            SnapshotSync.LOGGER.info("Idle detected; waiting {}s before snapshot",
                    Config.idleDelaySeconds);
            return;
        }

        if (now - idleSinceMillis < Config.idleDelaySeconds * 1000L) {
            return;
        }

        double tps = tps(server);
        double minimum = Math.max(Config.minTpsForSnapshot, HARD_FLOOR_TPS);
        if (tps < minimum) {
            // Stay armed: a lagging server should build later, not skip its snapshot.
            SnapshotSync.LOGGER.info("Idle but TPS {} below {}; deferring",
                    String.format(java.util.Locale.ROOT, "%.2f", tps), minimum);
            return;
        }

        submitBuild();
    }

    /**
     * Platform-aware idleness.
     *
     * <p>On a LAN host the owner is always present, so "no players" never happens; there we
     * require the world to be shared and nobody but the host to be connected.
     */
    static boolean isIdleEnough(MinecraftServer server) {
        PlayerList players = server.getPlayerList();
        if (players == null) {
            return true;
        }

        int count = players.getPlayerCount();

        if (!server.isSingleplayer()) {
            return count == 0;
        }

        if (!isPublishedToLan(server)) {
            // Not shared: there is nobody to serve, so this is not an idle window.
            return false;
        }

        return count <= 1;
    }

    private static boolean isPublishedToLan(MinecraftServer server) {
        if (server instanceof net.minecraft.client.server.IntegratedServer integrated) {
            return integrated.isPublished();
        }
        return true;
    }

    private static void submitBuild() {
        if (!BUILDING.compareAndSet(false, true)) {
            return;
        }

        // Disarm before the build so it cannot be triggered twice.
        disarm();

        BUILDER.execute(() -> {
            boolean ok = false;
            try {
                SnapshotBuilder.BuildResult result = SnapshotBuilder.buildAndPublish();
                ok = result.manifest() != null;

                lastChangeSummary = result.changed();
                lastBuildPublished = result.published();

                if (result.published()) {
                    SnapshotSync.LOGGER.info("Snapshot {} published ({} files changed)",
                            result.manifest().snapshotId(), result.changed().total());
                } else if (result.manifest() != null) {
                    SnapshotSync.LOGGER.info(
                            "Build ran but content was unchanged; kept snapshot {} (no repack)",
                            result.manifest().snapshotId());
                }
            } catch (Throwable t) {
                SnapshotSync.LOGGER.error("Snapshot build failed", t);
            } finally {
                BUILDING.set(false);

                // The clock is advanced even on failure. In INTERVAL mode that means a broken
                // pack waits a full interval before retrying instead of hammering once a
                // second; in IDLE mode lastBuildMillis is only informational.
                if (Config.packMode == PackMode.INTERVAL) {
                    lastBuildMillis = System.currentTimeMillis();
                }

                if (!ok) {
                    if (Config.packMode == PackMode.INTERVAL) {
                        SnapshotSync.LOGGER.warn("INTERVAL build failed; retrying in {} minute(s)",
                                Config.intervalMinutes);
                    } else {
                        rearmAfterBuild = true;
                    }
                }

                if (rearmAfterBuild) {
                    rearmAfterBuild = false;
                    arm("re-arm requested during build");
                }
            }
        });
    }

    /** Force a rebuild now, regardless of mode or idleness; used by {@code /snapsync rebuild}. */
    public static void forceRebuild() {
        if (Config.packMode == PackMode.INTERVAL) {
            // Reset the clock so the schedule continues from this build.
            lastBuildMillis = System.currentTimeMillis();
        }
        arm("manual rebuild");
        submitBuild();
    }

    /**
     * TPS from the smoothed tick time: {@code getCurrentSmoothedTickTime()} is milliseconds per
     * tick, so a healthy server sits near 50ms = 20 TPS.
     */
    public static double tps(MinecraftServer server) {
        float mspt = server.getCurrentSmoothedTickTime();
        if (mspt <= 0f) {
            return 20.0;
        }
        return Math.min(20.0, 20.0 / Math.max(1.0, mspt / 50.0));
    }

    public static long lastBuildMillis() {
        return lastBuildMillis;
    }

    /** What the last build changed; empty when it found nothing new. */
    public static com.snapsync.core.ChangeSummary lastChangeSummary() {
        return lastChangeSummary;
    }

    /** Whether the last build actually published a new snapshot id. */
    public static boolean lastBuildPublished() {
        return lastBuildPublished;
    }
}
