package io.github.carlosphenomenal.dr41.execution;

import io.github.carlosphenomenal.dr41.config.AppProperties;
import io.github.carlosphenomenal.dr41.error.ApiException;
import io.github.carlosphenomenal.dr41.k6.K6Runtime;
import io.github.carlosphenomenal.dr41.monitor.ResourceMonitor;
import io.github.carlosphenomenal.dr41.plan.PlanResolver;
import io.github.carlosphenomenal.dr41.plan.ResolvedPlan;
import io.github.carlosphenomenal.dr41.plan.TestPlanRequest;
import io.github.carlosphenomenal.dr41.report.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
public class TestRunService {
    private static final Logger log = LoggerFactory.getLogger(TestRunService.class);

    public record Validation(boolean valid, PlanView plan, List<String> warnings, K6Runtime.Status k6,
                             long estimatedMemoryMb, long availableMemoryMb, int resolutionSeconds) {}

    private final Map<UUID, TestRun> runs = new ConcurrentHashMap<>();
    private final AppProperties props;
    private final PlanResolver resolver;
    private final K6Runtime k6Runtime;
    private final ResourceMonitor monitor;
    private final TestRunner runner;
    private final ReportStore store;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;

    public TestRunService(AppProperties props, PlanResolver resolver, K6Runtime k6Runtime, ResourceMonitor monitor,
                          TestRunner runner, ReportStore store,
                          @Qualifier("runExecutor") ExecutorService executor,
                          @Qualifier("housekeepingScheduler") ScheduledExecutorService scheduler) {
        this.props = props;
        this.resolver = resolver;
        this.k6Runtime = k6Runtime;
        this.monitor = monitor;
        this.runner = runner;
        this.store = store;
        this.executor = executor;
        this.scheduler = scheduler;
    }

    @PostConstruct
    void init() {
        // Leftovers from a crash: run directories hold scripts (with secrets), so remove them at start-up.
        Path runsDir = props.storage().workPath().resolve("runs");
        if (Files.isDirectory(runsDir)) {
            try (Stream<Path> s = Files.list(runsDir)) {
                s.forEach(p -> {
                    try (Stream<Path> walk = Files.walk(p)) {
                        walk.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
                    } catch (IOException ignored) { /* best effort */ }
                });
            } catch (IOException ignored) { /* best effort */ }
        }
        store.purgeExpired();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                store.purgeExpired();
                evictFinished();
            } catch (Throwable t) {
                log.debug("Housekeeping failed: {}", t.toString());
            }
        }, 1, 1, TimeUnit.HOURS);
    }

    /** Kill every k6 child on shutdown so no orphaned load generators keep hammering a target. */
    @PreDestroy
    void shutdown() {
        for (TestRun r : runs.values()) {
            if (!r.status().isTerminal()) {
                r.requestCancel();
                runner.kill(r.process());
            }
        }
    }

    // -------------------------------------------------------------------------------------- commands

    public synchronized TestRun submit(TestPlanRequest request) {
        ResolvedPlan plan = resolver.resolve(request);
        String memoryProblem = memoryProblem(plan);
        if (memoryProblem != null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_MEMORY", memoryProblem,
                    List.of("Lower virtualUsers, use mode: SEQUENTIAL, or free up memory. Adjust app.limits.estimated-memory-per-vu-mb if the estimate is too conservative."));
        }
        k6Runtime.require(); // fail fast with install guidance instead of queueing a doomed run
        evictFinished();

        AppProperties.Limits lim = props.limits();
        long active = runs.values().stream().filter(r -> !r.status().isTerminal()).count();
        if (active >= (long) lim.maxConcurrentTests() + lim.maxQueuedTests()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "QUEUE_FULL",
                    "Too many tests are running or queued (" + active + "). Cancel one or wait for it to finish.");
        }
        TestRun run = new TestRun(UUID.randomUUID(), plan, resolution(plan));
        runs.put(run.id(), run);
        try {
            executor.submit(() -> runner.execute(run));
        } catch (RejectedExecutionException e) {
            runs.remove(run.id());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SHUTTING_DOWN", "The application is shutting down.");
        }
        return run;
    }

    /** Dry run: full validation and capacity check without executing anything. */
    public Validation validate(TestPlanRequest request) {
        ResolvedPlan plan = resolver.resolve(request);
        List<String> warnings = new ArrayList<>(plan.warnings());
        String mem = memoryProblem(plan);
        if (mem != null) warnings.add("Would be rejected: " + mem);
        K6Runtime.Status k6 = k6Runtime.status();
        if (!k6.available()) warnings.add("k6 is not available: " + k6.problem());
        if (monitor.available() && plan.peakVus() > monitor.logicalCpus() * 500L) {
            warnings.add("More than 500 virtual users per CPU core: the load generator may saturate before the target does.");
        }
        long avail = monitor.availableMemoryBytes();
        return new Validation(mem == null && k6.available(), plan.toView(), warnings, k6, estimatedMb(plan),
                avail < 0 ? -1 : avail / (1024 * 1024), resolution(plan));
    }

    public TestRun cancel(UUID id) {
        TestRun run = get(id);
        if (run.status().isTerminal()) return run;
        run.requestCancel();
        if (run.transition(RunStatus.QUEUED, RunStatus.CANCELLED)) {
            runner.completeUnstarted(run);
        } else {
            runner.terminate(run.process()); // the runner thread observes EOF and finalises with partial data
        }
        return run;
    }

    // ---------------------------------------------------------------------------------------- queries

    public TestRun get(UUID id) {
        TestRun r = runs.get(id);
        if (r == null) throw ApiException.runNotFound(id);
        return r;
    }

    public TestReport report(UUID id) {
        TestRun r = runs.get(id);
        if (r != null) {
            if (r.report() != null) return r.report();
            throw new ApiException(HttpStatus.CONFLICT, "RUN_NOT_FINISHED",
                    "The run is " + r.status() + "; its report is available once it finishes.");
        }
        return store.load(id).orElseThrow(() -> ApiException.runNotFound(id));
    }

    public String logs(UUID id) {
        TestRun r = runs.get(id);
        if (r != null) return r.logs().snapshot();
        return store.load(id).map(TestReport::logTail).orElseThrow(() -> ApiException.runNotFound(id));
    }

    public List<RunView> list() {
        return runs.values().stream()
                .sorted(Comparator.comparing(TestRun::createdAt).reversed())
                .map(this::view).toList();
    }

    public int activeRuns() { return (int) runs.values().stream().filter(r -> r.status() == RunStatus.RUNNING || r.status() == RunStatus.PREPARING).count(); }
    public int queuedRuns() { return (int) runs.values().stream().filter(r -> r.status() == RunStatus.QUEUED).count(); }

    public RunView view(TestRun r) {
        RunStatus st = r.status();
        Instant end = r.completedAt() != null ? r.completedAt() : Instant.now();
        Long elapsed = r.startedAt() == null ? null : Duration.between(r.startedAt(), end).getSeconds();
        int planned = r.plan().totalSeconds();
        int progress = st == RunStatus.COMPLETED ? 100
                : elapsed == null ? 0 : (int) Math.min(99, elapsed * 100 / Math.max(1, planned));
        TestReport rep = r.report();
        String base = "/api/tests/" + r.id();
        Map<String, String> links = new LinkedHashMap<>();
        links.put("self", base);
        links.put("report", base + "/report");
        links.put("logs", base + "/logs");
        links.put("timeseriesCsv", base + "/timeseries.csv");
        links.put("resourcesCsv", base + "/resources.csv");
        return new RunView(r.id(), r.plan().name(), st, rep == null ? null : rep.verdict(), r.createdAt(), r.startedAt(),
                r.completedAt(), elapsed, planned, progress, r.exitCode(), r.error(),
                rep != null ? rep.warnings() : r.plan().warnings(), r.live(), r.latestResources(), links);
    }

    // ------------------------------------------------------------------------------------- internals

    private int resolution(ResolvedPlan plan) {
        return Math.max(1, (int) Math.ceil(plan.totalSeconds() / (double) props.limits().maxTimelinePoints()));
    }

    private long estimatedMb(ResolvedPlan plan) {
        return (long) plan.peakVus() * props.limits().estimatedMemoryPerVuMb();
    }

    private String memoryProblem(ResolvedPlan plan) {
        long avail = monitor.availableMemoryBytes();
        if (avail < 0) return null; // unknown: do not block
        long needMb = estimatedMb(plan);
        long allowedMb = (long) (avail / (1024.0 * 1024.0) * props.limits().memoryHeadroomRatio());
        return needMb > allowedMb
                ? "Peak " + plan.peakVus() + " virtual users needs roughly " + needMb + " MB but only " + allowedMb
                  + " MB is safely available; k6 would likely be OOM-killed."
                : null;
    }

    private void evictFinished() {
        int max = props.storage().maxFinishedRunsInMemory();
        List<TestRun> finished = runs.values().stream().filter(r -> r.status().isTerminal())
                .sorted(Comparator.comparing(TestRun::createdAt)).toList();
        for (int i = 0; i < finished.size() - max; i++) runs.remove(finished.get(i).id()); // reports stay on disk
    }
}
