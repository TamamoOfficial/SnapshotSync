import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone installer that runs AFTER the game process has exited.
 *
 * <h2>Why this is a separate program</h2>
 * Windows holds mod jars open for the whole session: deleting or renaming one fails with
 * "file is in use", so an update can only be applied once the game is really gone.
 *
 * <p>Earlier versions did this with a generated {@code .cmd} script, which turned out to be
 * fragile for reasons that are specific to batch files: {@code tasklist} returns exit code 0
 * even when no process matches, {@code find} does substring matching, and {@code errorlevel}
 * after a {@code move} is easy to get wrong. A JVM has none of those problems -
 * {@link ProcessHandle#of(long)} answers the liveness question directly, and file operations
 * report exceptions instead of magic exit codes.
 *
 * <h2>Why it is run as source, not from the mod jar</h2>
 * The mod's own jar is one of the files that may need replacing, and a running JVM keeps it
 * locked. Launching this file as a single-file source program means it depends on nothing in
 * {@code mods/}, so it can replace anything there - including SnapshotSync itself.
 *
 * <h2>Usage</h2>
 * <pre>
 *   java Applier.java &lt;parentPid&gt; &lt;planFile&gt;
 * </pre>
 * The plan file holds one {@code source|target} pair per line.
 */
public class Applier {

    /** How long to keep waiting for the game process before giving up, in milliseconds. */
    private static final long MAX_WAIT_MS = 6L * 60 * 60 * 1000; // 6 hours
    private static final long POLL_MS = 250;

    public static void main(String[] args) {
        if (args.length < 2) {
            return;
        }

        long parentPid;
        try {
            parentPid = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            return;
        }

        Path planFile = Path.of(args[1]);
        // plan.txt lives in <snapsync>/pending/, but the log and the completion marker are
        // read by the game from <snapsync>/, so step up one level.
        Path snapsyncDir = planFile.getParent() != null
                ? planFile.getParent().getParent()
                : planFile.getParent();
        Path logFile = snapsyncDir.resolve("apply-update.log");

        log(logFile, "applier started, waiting for pid " + parentPid);

        if (!waitForExit(parentPid)) {
            log(logFile, "gave up waiting for pid " + parentPid);
            return;
        }

        log(logFile, "game process gone, installing");

        List<String[]> plan = readPlan(planFile, logFile);
        if (plan.isEmpty()) {
            log(logFile, "nothing to install");
            return;
        }

        int ok = 0;
        int failed = 0;

        for (String[] pair : plan) {
            String from = pair[0];
            String to = pair[1];
            String name = Path.of(to).getFileName().toString();

            Path source = Path.of(from);
            Path target = Path.of(to);
            Path backup = Path.of(to + ".snapsync-old");

            if (!Files.isRegularFile(source)) {
                log(logFile, "SKIP " + name + " (staged file missing)");
                continue;
            }

            try {
                Files.createDirectories(target.getParent());

                // Keep the previous jar until the new one is in place, so a failure can be
                // undone rather than leaving the player without a working mod.
                if (Files.isRegularFile(target)) {
                    Files.deleteIfExists(backup);
                    Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
                }

                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(backup);
                ok++;
                log(logFile, "OK " + name);
            } catch (IOException e) {
                failed++;
                log(logFile, "FAILED " + name + ": " + e.getMessage());

                // Restore the old jar so a failed update never breaks the client.
                try {
                    if (Files.isRegularFile(backup) && !Files.isRegularFile(target)) {
                        Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
                        log(logFile, "restored " + name);
                    }
                } catch (IOException restoreError) {
                    log(logFile, "could not restore " + name + ": " + restoreError.getMessage());
                }
            }
        }

        log(logFile, "finished: " + ok + " installed, " + failed + " failed");

        // Mark completion so the game can report it next launch.
        try {
            Files.writeString(snapsyncDir.resolve("update-applied"),
                    ok + " installed, " + failed + " failed\n");
        } catch (IOException ignored) {
            // completion marker is informational only
        }

        try {
            Files.deleteIfExists(planFile);
        } catch (IOException ignored) {
            // leaving the plan behind is harmless
        }
    }

    /**
     * Blocks until the given process no longer exists.
     *
     * @return true if the process is gone, false if we waited too long
     */
    private static boolean waitForExit(long pid) {
        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;

        while (System.currentTimeMillis() < deadline) {
            // ProcessHandle answers this directly; no shell parsing involved.
            if (ProcessHandle.of(pid).isEmpty()) {
                // Give the OS a moment to release file handles after process death.
                sleep(600);
                return true;
            }
            sleep(POLL_MS);
        }
        return false;
    }

    private static List<String[]> readPlan(Path planFile, Path logFile) {
        List<String[]> plan = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(planFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int bar = trimmed.indexOf('|');
                if (bar <= 0 || bar == trimmed.length() - 1) {
                    log(logFile, "ignoring malformed plan line: " + trimmed);
                    continue;
                }

                String from = trimmed.substring(0, bar).trim();
                String to = trimmed.substring(bar + 1).trim();

                // Only real file paths count. This guards against reading a human-readable
                // summary by mistake - which is exactly what happened when the summary file
                // was named PLAN.txt and collided with plan.txt on a case-insensitive
                // filesystem, leaving "nothing to install" while the jars sat in pending.
                if (!looksLikePath(from) || !looksLikePath(to)) {
                    log(logFile, "ignoring non-path plan line: " + trimmed);
                    continue;
                }

                plan.add(new String[]{from, to});
            }
        } catch (IOException e) {
            log(logFile, "could not read plan: " + e.getMessage());
        }
        return plan;
    }

    /** A plan entry must be an absolute Windows or POSIX path. */
    private static boolean looksLikePath(String value) {
        if (value.length() < 4 || value.startsWith("#")) {
            return false;
        }
        boolean windows = value.length() > 2 && value.charAt(1) == ':';
        boolean posix = value.charAt(0) == '/';
        return windows || posix;
    }

    private static void log(Path logFile, String message) {
        try {
            Files.writeString(logFile,
                    "[" + java.time.LocalTime.now().withNano(0) + "] " + message + "\n",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // logging must never break the install
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
