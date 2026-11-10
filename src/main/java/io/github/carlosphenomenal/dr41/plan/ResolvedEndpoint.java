package io.github.carlosphenomenal.dr41.plan;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** A fully validated endpoint with concrete stages. Contains secrets (headers/body): never serialise directly. */
public record ResolvedEndpoint(
        int index,
        String name,
        String url,
        String method,
        Map<String, String> headers,
        String body,
        int expectedStatus,
        int thinkTimeMs,
        int timeoutSeconds,
        Double p95Ms,
        Double p99Ms,
        Double errorRate,
        List<Stage> stages,
        int startOffsetSeconds) {

    public int durationSeconds() { return stages.stream().mapToInt(Stage::durationSeconds).sum(); }
    public int peakVus() { return stages.stream().mapToInt(Stage::target).max().orElse(0); }
    public URI uri() { return URI.create(url); }

    public ResolvedEndpoint withStartOffset(int seconds) {
        return new ResolvedEndpoint(index, name, url, method, headers, body, expectedStatus, thinkTimeMs,
                timeoutSeconds, p95Ms, p99Ms, errorRate, stages, seconds);
    }
}
