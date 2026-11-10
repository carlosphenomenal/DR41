package io.github.carlosphenomenal.dr41.report;

/** One time bucket for one series. {@code t} is the bucket start in epoch seconds. */
public record TimelinePoint(long t, long requests, long errors, double rps,
                            double avgMs, double p50Ms, double p95Ms, double p99Ms, double maxMs) {}
