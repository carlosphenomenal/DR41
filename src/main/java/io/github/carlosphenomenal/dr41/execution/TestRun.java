package io.github.carlosphenomenal.dr41.execution;

import io.github.carlosphenomenal.dr41.metrics.MetricsAggregator;
import io.github.carlosphenomenal.dr41.monitor.ResourceMonitor;
import io.github.carlosphenomenal.dr41.plan.ResolvedEndpoint;
import io.github.carlosphenomenal.dr41.plan.ResolvedPlan;
import io.github.carlosphenomenal.dr41.report.ErrorInfo;
import io.github.carlosphenomenal.dr41.report.LiveMetrics;
import io.github.carlosphenomenal.dr41.report.ResourceSample;
import io.github.carlosphenomenal.dr41.report.RunStatus;
import io.github.carlosphenomenal.dr41.report.TestReport;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable state of one run. Status changes go through compare-and-set so that cancel-while-queued and
 * start-running can never both win. Heavy state (aggregator, process, sampler) is dropped on completion;
 * only the compact report is kept.
 */
public final class TestRun {
    private final UUID id;
    private final ResolvedPlan plan;
    private final Instant createdAt = Instant.now();
    private final int resolutionSeconds;
    private final AtomicReference<RunStatus> status = new AtomicReference<>(RunStatus.QUEUED);
    private final LogTail logs = new LogTail();

    private volatile MetricsAggregator metrics;
    private volatile ResourceMonitor.Session resources;
    private volatile Process process;
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile Integer exitCode;
    private volatile ErrorInfo error;
    private volatile TestReport report;
    private volatile boolean cancelRequested;
    private volatile boolean timedOut;

    public TestRun(UUID id, ResolvedPlan plan, int resolutionSeconds) {
        this.id = id;
        this.plan = plan;
        this.resolutionSeconds = resolutionSeconds;
        this.metrics = new MetricsAggregator(plan.endpoints().stream().map(ResolvedEndpoint::name).toList(), resolutionSeconds);
    }

    public UUID id() { return id; }
    public ResolvedPlan plan() { return plan; }
    public Instant createdAt() { return createdAt; }
    public int resolutionSeconds() { return resolutionSeconds; }
    public RunStatus status() { return status.get(); }
    public LogTail logs() { return logs; }
    public MetricsAggregator metrics() { return metrics; }
    public Process process() { return process; }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
    public Integer exitCode() { return exitCode; }
    public ErrorInfo error() { return error; }
    public TestReport report() { return report; }
    public boolean cancelRequested() { return cancelRequested; }
    public boolean timedOut() { return timedOut; }

    public boolean transition(RunStatus from, RunStatus to) { return status.compareAndSet(from, to); }
    public void requestCancel() { cancelRequested = true; }
    public void markTimedOut() { timedOut = true; }
    public void setProcess(Process p) { this.process = p; }
    public void setResources(ResourceMonitor.Session s) { this.resources = s; }

    public void markRunning() {
        this.startedAt = Instant.now();
        status.set(RunStatus.RUNNING);
    }

    public LiveMetrics live() {
        MetricsAggregator m = metrics;
        return m == null ? null : m.live();
    }

    public ResourceSample latestResources() {
        ResourceMonitor.Session s = resources;
        return s == null ? null : s.latest();
    }

    /** Publishes the final state. The status is set last so readers that see a terminal status also see the report. */
    public void complete(TestReport finalReport, RunStatus finalStatus, Integer exit, ErrorInfo err, Instant at) {
        this.report = finalReport;
        this.exitCode = exit;
        this.error = err;
        this.completedAt = at;
        this.metrics = null;
        this.resources = null;
        this.process = null;
        this.status.set(finalStatus);
    }
}
