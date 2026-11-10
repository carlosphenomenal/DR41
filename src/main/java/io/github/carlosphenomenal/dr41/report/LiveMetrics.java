package io.github.carlosphenomenal.dr41.report;

public record LiveMetrics(long requests, long errors, double errorRate, double currentRps,
                          double p95Ms, int currentVus) {}
