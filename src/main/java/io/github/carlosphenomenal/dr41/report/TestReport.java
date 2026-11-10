package io.github.carlosphenomenal.dr41.report;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The complete, persisted outcome of a run: summary statistics plus graph-ready time series. */
public record TestReport(
        UUID id,
        String name,
        String testType,
        String mode,
        RunStatus status,
        String verdict,            // PASSED | THRESHOLDS_BREACHED | null when not completed
        boolean partial,           // true when cancelled/failed mid-way: numbers cover what ran
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        long elapsedSeconds,
        K6Run k6,
        ErrorInfo error,
        List<String> warnings,
        PlanView plan,
        EndpointSummary overall,
        Map<String, EndpointSummary> endpoints,
        Timeline timeline,
        ResourceReport resources,
        HardwareInfo hardware,
        String logTail) {

    public record K6Run(String version, Integer exitCode, boolean thresholdsBreached) {}
}
