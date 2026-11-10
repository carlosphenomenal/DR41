package io.github.carlosphenomenal.dr41.execution;

import io.github.carlosphenomenal.dr41.config.AppProperties;
import io.github.carlosphenomenal.dr41.error.ApiException;
import io.github.carlosphenomenal.dr41.k6.K6Runtime;
import io.github.carlosphenomenal.dr41.k6.K6ScriptGenerator;
import io.github.carlosphenomenal.dr41.k6.K6StreamParser;
import io.github.carlosphenomenal.dr41.metrics.MetricsAggregator;
import io.github.carlosphenomenal.dr41.monitor.ResourceMonitor;
import io.github.carlosphenomenal.dr41.plan.ResolvedPlan;
import io.github.carlosphenomenal.dr41.report.*;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Executes one run end to end:
 *   wait for a slot -> verify k6 -> probe targets -> write script -> start k6 -> stream metrics
 *   -> (watchdog / cancel) -> classify exit -> build report -> persist -> clean up.
 * <p>
 * Design notes
 *  - stdout (metrics) and stderr (logs) are drained concurrently; draining only one can deadlock k6 when a pipe fills.
 *  - each run gets its own REST API port: k6's default (6565) makes concurrent/overlapping runs fail.
 *  - the script (which contains header values) lives in a private temp dir that is always deleted afterwards.
 *  - a report is produced for every outcome, including failures and cancellations (partial data is still useful).
 */
@Component
public class TestRunner {
    private static final Logger log = LoggerFactory.getLogger(TestRunner.class);

    private final AppProperties props;
    private final K6Runtime k6Runtime;
    private final K6ScriptGenerator scriptGenerator;
    private final TargetProbe probe;
    private final ResourceMonitor monitor;
    private final ReportStore store;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final Semaphore slots;

    public TestRunner(AppProperties props, K6Runtime k6Runtime, K6ScriptGenerator scriptGenerator, TargetProbe probe,
                      ResourceMonitor monitor, ReportStore store,
                      @Qualifier("runExecutor") ExecutorService executor,
                      @Qualifier("housekeepingScheduler") ScheduledExecutorService scheduler) {
        this.props = props;
        this.k6Runtime = k6Runtime;
        this.scriptGenerator = scriptGenerator;
        this.probe = probe;
        this.monitor = monitor;
        this.store = store;
        this.executor = executor;
        this.scheduler = scheduler;
        this.slots = new Semaphore(Math.max(1, props.limits().maxConcurrentTests()), true);
    }

    /** Mutable scratchpad for one execution. */
    private static final class Ctx {
        RunStatus status = RunStatus.FAILED;
        ErrorInfo error;
        Integer exit;
        String k6Version;
        boolean breached;
        final List<String> warnings = new ArrayList<>();
        Path runDir;
        ResourceMonitor.Session resources;
        ScheduledFuture<?> watchdog;
        boolean started;
    }

    private static final class RunFailure extends Exception {
        final String code;
        final List<String> details;
        final boolean cancelled;

        RunFailure(String code, String message, List<String> details) {
            super(message);
            this.code = code;
            this.details = details;
            this.cancelled = false;
        }

        private RunFailure() {
            super("Cancelled");
            this.code = "CANCELLED";
            this.details = List.of();
            this.cancelled = true;
        }

        static RunFailure cancelled() { return new RunFailure(); }
    }

    // ------------------------------------------------------------------------------------------ entry

    public void execute(TestRun run) {
        Ctx ctx = new Ctx();
        boolean acquired = false;
        try {
            slots.acquire();
            acquired = true;
            if (!run.transition(RunStatus.QUEUED, RunStatus.PREPARING)) return; // cancelled while queued: already finalised
            ctx.started = true;
            runProcess(run, ctx);
        } catch (RunFailure f) {
            if (f.cancelled) {
                ctx.status = RunStatus.CANCELLED;
            } else {
                ctx.status = RunStatus.FAILED;
                ctx.error = new ErrorInfo(f.code, f.getMessage(), f.details);
            }
        } catch (ApiException e) {
            ctx.status = RunStatus.FAILED;
            ctx.error = new ErrorInfo(e.getCode(), e.getMessage(), e.getDetails());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ctx.status = RunStatus.CANCELLED;
            ctx.warnings.add("The worker was interrupted (application shutting down).");
        } catch (Throwable t) {
            log.error("Run {} crashed", run.id(), t);
            ctx.status = RunStatus.FAILED;
            ctx.error = new ErrorInfo("INTERNAL_ERROR", "Unexpected error: " + t.getMessage(), List.of());
        } finally {
            if (ctx.watchdog != null) ctx.watchdog.cancel(false);
            Process p = run.process();
            if (p != null && p.isAlive()) kill(p);
            deleteQuietly(ctx.runDir);
            if (ctx.started) finalizeRun(run, ctx);
            if (acquired) slots.release();
        }
    }

    /** Called by the service when a still-queued run is cancelled (its status is already CANCELLED). */
    public void completeUnstarted(TestRun run) {
        Ctx ctx = new Ctx();
        ctx.status = RunStatus.CANCELLED;
        ctx.warnings.add("Cancelled while queued; k6 never started.");
        finalizeRun(run, ctx);
    }

    // ------------------------------------------------------------------------------------- the process

    private void runProcess(TestRun run, Ctx ctx) throws RunFailure, IOException, InterruptedException {
        ResolvedPlan plan = run.plan();

        K6Runtime.K6Info k6 = k6Runtime.require();
        ctx.k6Version = k6.version();
        checkCancelled(run);

        List<String> unreachable = probe.unreachable(plan.endpoints());
        if (!unreachable.isEmpty()) {
            throw new RunFailure("TARGET_UNREACHABLE", "One or more targets cannot be reached from this machine.", unreachable);
        }
        checkCancelled(run);

        Path dir = prepareRunDir(run, ctx);
        Path script = dir.resolve("test.js");
        writePrivate(script, scriptGenerator.generate(plan));
        int port = freePort();

        List<String> cmd = List.of(k6.executable().toString(), "run", "--quiet", "--no-color",
                "--out", "json=-", "--address", "127.0.0.1:" + port, script.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        Map<String, String> env = pb.environment();
        env.put("K6_NO_USAGE_REPORT", "true");
        env.remove("K6_OUT");            // user-level k6 config must not add outputs or change our stream
        env.remove("K6_WEB_DASHBOARD");
        env.remove("K6_ADDRESS");
        checkCancelled(run);

        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new RunFailure("K6_START_FAILED", "k6 could not be started: " + e.getMessage(),
                    List.of("Check that the k6 binary is executable and matches this OS/CPU architecture."));
        }
        run.setProcess(p);
        run.markRunning();
        ctx.resources = monitor.start(run.resolutionSeconds(), p::pid);
        run.setResources(ctx.resources);

        long limit = plan.totalSeconds() + props.k6().hardTimeoutGrace().toSeconds() + 15;
        ctx.watchdog = scheduler.schedule(() -> {
            run.markTimedOut();
            terminate(p);
        }, limit, TimeUnit.SECONDS);
        if (run.cancelRequested()) terminate(p); // cancel arrived while we were starting

        Future<?> stderr = executor.submit(() -> drain(p.getErrorStream(), run.logs()));
        K6StreamParser parser = new K6StreamParser(run.metrics());
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) parser.accept(line);
        } catch (IOException e) {
            if (!run.cancelRequested() && !run.timedOut()) ctx.warnings.add("The metrics stream ended unexpectedly: " + e.getMessage());
        }

        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
        }
        int exit = p.isAlive() ? -1 : p.exitValue();
        try {
            stderr.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            stderr.cancel(true);
        }
        ctx.exit = exit;

        if (parser.linesRejected() > 0) ctx.warnings.add(parser.linesRejected() + " metric line(s) could not be parsed and were skipped.");
        if (run.cancelRequested()) {
            ctx.status = RunStatus.CANCELLED;
        } else if (run.timedOut()) {
            ctx.status = RunStatus.FAILED;
            ctx.error = new ErrorInfo("TIMEOUT", "k6 did not finish within " + limit + "s and was killed.", run.logs().last(8));
        } else if (exit == 0) {
            ctx.status = RunStatus.COMPLETED;
        } else if (exit == 99) {
            ctx.status = RunStatus.COMPLETED;
            ctx.breached = true;
        } else {
            ctx.status = RunStatus.FAILED;
            ctx.error = describeExit(exit, run.logs());
        }
        if (parser.firstRejectedSample() != null) {
            ctx.warnings.add("Example of an unparsable metric line: " + parser.firstRejectedSample());
        }
        if (ctx.status == RunStatus.COMPLETED && parser.pointsParsed() == 0) {
            ctx.warnings.add("k6 finished but no request metrics were captured. "
                    + (parser.firstPointSample() == null
                    ? "k6 emitted no data points on stdout at all (check the log tail and your k6 version's --out json=- support)."
                    : "First data point k6 emitted: " + parser.firstPointSample()));
        }
    }

    private static void checkCancelled(TestRun run) throws RunFailure {
        if (run.cancelRequested()) throw RunFailure.cancelled();
    }

    private Path prepareRunDir(TestRun run, Ctx ctx) throws RunFailure {
        Path dir = props.storage().workPath().resolve("runs").resolve(run.id().toString());
        try {
            Files.createDirectories(dir);
            ctx.runDir = dir;
            if (Files.getFileStore(dir).getUsableSpace() < 64L * 1024 * 1024) {
                throw new RunFailure("INSUFFICIENT_DISK_SPACE", "Less than 64 MB of free disk space in " + dir,
                        List.of("Free up space or set DR41_WORK_DIR to another drive."));
            }
            return dir;
        } catch (IOException e) {
            throw new RunFailure("WORKDIR_NOT_WRITABLE", "Cannot use working directory " + dir + ": " + e.getMessage(),
                    List.of("Set DR41_WORK_DIR (app.storage.work-directory) to a writable location."));
        }
    }

    private static void writePrivate(Path file, String content) throws IOException {
        try {
            Files.createFile(file, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) { // Windows
            Files.createFile(file);
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private static void drain(InputStream in, LogTail tail) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) tail.add(line);
        } catch (IOException ignored) { /* process ended */ }
    }

    private static ErrorInfo describeExit(int exit, LogTail logs) {
        String msg = switch (exit) {
            case 104 -> "k6 rejected the generated configuration.";
            case 103 -> "k6 hit an internal engine error.";
            case 106 -> "k6 could not start its internal API server.";
            case 107 -> "The generated k6 script threw an exception.";
            case 108 -> "k6 aborted the script.";
            case 100, 101, 102 -> "k6 timed out during setup/teardown.";
            case 137 -> "k6 was killed by the OS (SIGKILL), most likely out of memory. Reduce virtualUsers.";
            case -1 -> "k6 did not exit cleanly and was force-killed.";
            default -> "k6 exited with code " + exit + ".";
        };
        return new ErrorInfo("K6_EXIT_" + exit, msg, logs.last(8));
    }

    // ------------------------------------------------------------------------------- process control

    /** Graceful stop (SIGTERM / TerminateProcess) for the whole tree, then a forced kill if it lingers. */
    public void terminate(Process p) {
        if (p == null || !p.isAlive()) return;
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        long grace = props.k6().gracefulStop().toMillis() + 5_000;
        try {
            scheduler.schedule(() -> kill(p), grace, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            kill(p);
        }
    }

    public void kill(Process p) {
        if (p == null) return;
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    // -------------------------------------------------------------------------------------- reporting

    private void finalizeRun(TestRun run, Ctx ctx) {
        try {
            ResourceReport resources = ctx.resources != null ? ctx.resources.stop()
                    : ResourceReport.unavailable("k6 never started");
            MetricsAggregator agg = run.metrics();
            agg.finish();

            List<String> warnings = new ArrayList<>(run.plan().warnings());
            warnings.addAll(ctx.warnings);
            ResourceSummary rs = resources.summary();
            if (rs != null && rs.loadGeneratorSaturated()) {
                warnings.add("The load generator's CPU was above " + props.monitoring().saturationCpuPercent()
                        + "% for a significant part of the run. Latencies are likely inflated by this machine, not the target. "
                        + "Run from a stronger machine or reduce virtual users.");
            }
            if (rs != null && rs.peakMemoryPercent() >= 95) {
                warnings.add("Host memory use peaked at " + rs.peakMemoryPercent() + "%. The OS may have been swapping.");
            }

            Instant completed = Instant.now();
            Instant started = run.startedAt();
            long elapsed = started == null ? 0 : Duration.between(started, completed).getSeconds();
            RunStatus st = ctx.status;
            String verdict = st == RunStatus.COMPLETED ? (ctx.breached ? "THRESHOLDS_BREACHED" : "PASSED") : null;

            TestReport report = new TestReport(run.id(), run.plan().name(), run.plan().type().name(), run.plan().mode().name(),
                    st, verdict, st != RunStatus.COMPLETED, run.createdAt(), started, completed, elapsed,
                    new TestReport.K6Run(ctx.k6Version, ctx.exit, ctx.breached), ctx.error, List.copyOf(warnings),
                    run.plan().toView(), agg.overall(), agg.endpointSummaries(), agg.timeline(), resources,
                    monitor.hardware(), run.logs().snapshot());

            try {
                store.save(report);
            } catch (Exception e) {
                log.warn("Could not persist report {}: {}", run.id(), e.toString());
            }
            run.complete(report, st, ctx.exit, ctx.error, completed);
        } catch (Throwable t) {
            log.error("Could not finalise run {}", run.id(), t);
            run.complete(null, RunStatus.FAILED, ctx.exit,
                    new ErrorInfo("REPORT_FAILED", "The run ended but its report could not be built: " + t.getMessage(), List.of()),
                    Instant.now());
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(@NonNull Path f, @NonNull BasicFileAttributes a) throws IOException {
                    Files.deleteIfExists(f);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(@NonNull Path d, IOException e) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.debug("Could not fully delete {}: {}", dir, e.toString());
        }
    }
}
