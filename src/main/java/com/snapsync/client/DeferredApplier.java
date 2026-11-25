package com.snapsync.client;

import com.snapsync.SnapshotSync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stages files that cannot be written while the game is running, and launches a helper that
 * installs them once the game has exited.
 *
 * <h2>Why a helper at all</h2>
 * On Windows a jar held by the mod classloader cannot be deleted or renamed - measured, not
 * assumed. Overwriting in place is allowed but risks leaving a truncated jar if the process
 * dies mid-write, so the swap has to happen after the JVM is gone.
 *
 * <h2>Why the helper is a Java source file, not a .cmd script</h2>
 * The first implementation generated a batch file. It failed in practice for reasons that are
 * inherent to batch: {@code tasklist} exits 0 even when no process matches, {@code find} does
 * substring matching, and {@code errorlevel} after {@code move} is easy to misread - so the
 * script installed while the game was still running and then reported a failure.
 *
 * <p>The replacement is a plain Java program, shipped as a resource and written next to the
 * game. It is started with {@code java <file>.java}, which Java 21 supports directly. Because
 * it lives outside {@code mods/}, it is not itself locked, so it can even replace SnapshotSync.
 */
public final class DeferredApplier {

    /** Files whose replacement must wait until the game exits. */
    public static boolean needsDeferral(String relativePath) {
        String lower = relativePath.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".jar")) {
            return true;
        }
        return lower.endsWith(".zip") && lower.startsWith("mods/");
    }

    private DeferredApplier() {
    }

    public static Path pendingDir() {
        return Path.of(SnapshotSync.DIR, "pending");
    }

    private static Path applierSource() {
        return Path.of(SnapshotSync.DIR, "Applier.java");
    }

    private static Path planFile() {
        return Path.of(SnapshotSync.DIR, "pending", "plan.txt");
    }

    private static Path statusPath() {
        return Path.of(SnapshotSync.DIR, "apply-update.log");
    }

    /** One file to install after exit. */
    public record Pending(Path source, Path target, String sha256) {
    }

    /**
     * Copies deferred files into the pending folder and writes the install plan.
     *
     * @param newFiles relative path -> already-downloaded blob file
     * @return number of files staged
     */
    public static int stage(Map<String, Path> newFiles, Path gameDir) throws IOException {
        Path pending = pendingDir();
        Files.createDirectories(pending);

        Path planPath = planFile();
        List<String> planLines = new ArrayList<>();
        int staged = 0;

        for (Map.Entry<String, Path> e : newFiles.entrySet()) {
            String relative = e.getKey();
            if (!needsDeferral(relative)) {
                continue;
            }

            Path target = gameDir.resolve(relative).normalize();
            if (!target.startsWith(gameDir.normalize())) {
                SnapshotSync.LOGGER.warn("Refusing to stage outside game dir: {}", relative);
                continue;
            }

            // Keep the relative structure so identically named jars cannot collide.
            Path stagedFile = pending.resolve("files").resolve(relative).normalize();
            Files.createDirectories(stagedFile.getParent());
            Files.copy(e.getValue(), stagedFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            String actual = com.snapsync.core.Hashing.sha256Hex(stagedFile);
            String expected = e.getValue().getFileName().toString();
            if (!actual.equalsIgnoreCase(expected)) {
                SnapshotSync.LOGGER.warn("Staged copy of {} does not match its hash; skipping",
                        relative);
                continue;
            }

            planLines.add(stagedFile.toAbsolutePath() + "|" + target.toAbsolutePath());
            staged++;
        }

        if (planLines.isEmpty()) {
            return 0;
        }

        // The plan is the contract between the game and the helper.
        Files.write(planPath, planLines, StandardCharsets.UTF_8);

        extractApplier();
        writePlanSummary(planLines);

        if (startHelper()) {
            SnapshotSync.LOGGER.info(
                    "Update helper launched; {} jar(s) will be installed after the game exits",
                    staged);
        }

        return staged;
    }

    /** Writes the standalone applier next to the game, so it is not inside a locked jar. */
    private static void extractApplier() throws IOException {
        Path out = applierSource();
        if (Files.isRegularFile(out)) {
            return;
        }

        try (InputStream in = DeferredApplier.class.getResourceAsStream("/snapsync/Applier.java")) {
            if (in == null) {
                throw new IOException("Applier.java resource missing from the mod jar");
            }
            Files.createDirectories(out.getParent());
            Files.copy(in, out);
        }
    }

    private static void writePlanSummary(List<String> planLines) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("SnapshotSync will install these files when the game exits:\n\n");
        for (String line : planLines) {
            String target = line.substring(line.indexOf('|') + 1);
            sb.append("  ").append(Path.of(target).getFileName()).append('\n');
        }
        sb.append("\nPlan: ").append(planFile().toAbsolutePath()).append('\n');
        sb.append("Log:  ").append(statusPath().toAbsolutePath()).append('\n');
        Files.writeString(Path.of(SnapshotSync.DIR, "pending", "what-will-install.txt"), sb.toString());
    }

    private static volatile boolean helperStarted;

    /**
     * Starts the helper in a detached console.
     *
     * <p>It is launched as soon as jars are staged rather than only on shutdown, because
     * launchers routinely kill the game process and a {@code shutdownHook} never runs in that
     * case - which is exactly how an earlier version silently left jars uninstalled.
     */
    public static synchronized boolean startHelper() {
        if (helperStarted || !Files.isRegularFile(planFile())) {
            return false;
        }

        try {
            String java = ProcessHandle.current().info().command()
                    .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());

            pbStart(java);
            helperStarted = true;
            SnapshotSync.LOGGER.info("Update helper started");
            return true;
        } catch (IOException e) {
            SnapshotSync.LOGGER.error("Could not start the update helper", e);
            return false;
        }
    }

    /**
     * Starts the helper detached, using each platform's own mechanism.
     *
     * <p>There is no portable way to outlive the parent process:
     * <ul>
     *   <li><b>Windows</b>: a child started directly is killed with the parent, so it must be
     *       handed to {@code cmd /c start /b}. The empty {@code ""} is the window title - without
     *       it {@code start} consumes the (quoted, space-containing) java path as the title and
     *       then tries to run the first real argument.</li>
     *   <li><b>Linux / macOS</b>: use {@code setsid} when available so the helper gets its own
     *       session and is not killed by a terminal hangup; otherwise start it directly, which
     *       already survives the parent on POSIX.</li>
     *   <li><b>Android</b>: {@code setsid} and {@code nohup} are usually absent, and the
     *       launcher is an app process rather than a shell, so the direct path is used.</li>
     * </ul>
     */
    private static void pbStart(String javaExe) throws IOException {
        String applier = applierSource().toAbsolutePath().toString();
        String pid = Long.toString(ProcessHandle.current().pid());
        String plan = planFile().toAbsolutePath().toString();

        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        ProcessBuilder pb;

        if (os.contains("win")) {
            pb = new ProcessBuilder(
                    "cmd.exe", "/c", "start", "/b", "",
                    quoteIfNeeded(javaExe),
                    quoteIfNeeded(applier),
                    pid,
                    quoteIfNeeded(plan));
        } else if (os.contains("android")) {
            // No setsid/nohup on Android; a plain child of the launcher is the best we can do,
            // and the applier itself tolerates being started late because it polls the pid.
            pb = new ProcessBuilder(javaExe, applier, pid, plan);
        } else {
            pb = new ProcessBuilder(
                    hasCommand("setsid")
                            ? new String[]{"setsid", javaExe, applier, pid, plan}
                            : new String[]{javaExe, applier, pid, plan});
        }

        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectErrorStream(true);
        pb.start();
    }

    /** True when an executable is on PATH (used to prefer setsid but not require it). */
    private static boolean hasCommand(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, name))) {
                return true;
            }
        }
        return false;
    }

    /** Wraps a path in quotes when it contains a space, for cmd's benefit. */
    private static String quoteIfNeeded(String value) {
        return value.indexOf(' ') >= 0 ? "\"" + value + "\"" : value;
    }

    /** Backstop for the shutdown hook. */
    public static boolean launchOnExit() {
        return startHelper();
    }

    /** True when files are staged and waiting for the next exit. */
    public static boolean hasPendingUpdate() {
        return Files.isRegularFile(planFile());
    }

    /** Outcome of the last deferred install, for the status command. */
    public static String lastResult() {
        Path log = statusPath();
        if (!Files.isRegularFile(log)) {
            return "";
        }
        try {
            return Files.readString(log).trim();
        } catch (IOException e) {
            return "";
        }
    }

    public static void clearPending() {
        try {
            Files.deleteIfExists(planFile());
        } catch (IOException ignored) {
            // best effort
        }
    }
}
