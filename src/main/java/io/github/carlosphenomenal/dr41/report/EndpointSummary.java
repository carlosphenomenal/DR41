package io.github.carlosphenomenal.dr41.report;

import java.util.Map;

public record EndpointSummary(long requests, long errors, double errorRate, double rps,
                              double avgMs, double minMs, double p50Ms, double p90Ms,
                              double p95Ms, double p99Ms, double maxMs, Map<String, Long> statusCodes) {}
