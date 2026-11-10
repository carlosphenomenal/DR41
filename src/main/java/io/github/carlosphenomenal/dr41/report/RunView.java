package io.github.carlosphenomenal.dr41.report;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Lightweight status document returned while a run is queued/running and after it ends. */
public record RunView(
        UUID id,
        String name,
        RunStatus status,
        String verdict,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Long elapsedSeconds,
        int plannedSeconds,
        int progressPercent,
        Integer k6ExitCode,
        ErrorInfo error,
        List<String> warnings,
        LiveMetrics live,
        ResourceSample latestResources,
        Map<String, String> links) {}
