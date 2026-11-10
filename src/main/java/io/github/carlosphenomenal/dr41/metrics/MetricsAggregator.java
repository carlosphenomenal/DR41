package io.github.carlosphenomenal.dr41.metrics;

import io.github.carlosphenomenal.dr41.plan.PlanResolver;
import io.github.carlosphenomenal.dr41.report.EndpointSummary;
import io.github.carlosphenomenal.dr41.report.LiveMetrics;
import io.github.carlosphenomenal.dr41.report.Timeline;
import io.github.carlosphenomenal.dr41.report.TimelinePoint;
import io.github.carlosphenomenal.dr41.report.VusPoint;

import java.util.*;

/**
 * Consumes k6's raw per-request samples and keeps only:
 *  - one fixed-size histogram per endpoint (whole run),
 *  - a handful of "open" time buckets (the recent past),
 *  - a compact summary point for every closed bucket.
 * Memory is O(endpoints x timeline points), independent of the number of requests.
 * One writer thread (the k6 stdout pump); readers (HTTP) take the same lock briefly.
 */
public final class MetricsAggregator {
    private final int res;
    private final Map<String, Series> byEndpoint = new LinkedHashMap<>();
    private final Series all = new Series(PlanResolver.ALL_SERIES);
    private final TreeMap<Long, Integer> vus = new TreeMap<>();
    private long maxBucket = Long.MIN_VALUE;
    private long flushedBefore = Long.MIN_VALUE;
    private int lastVus;
    private long lastVusBucket = Long.MIN_VALUE;

    public MetricsAggregator(List<String> endpointNames, int resolutionSeconds) {
        this.res = Math.max(1, resolutionSeconds);
        for (String n : endpointNames) byEndpoint.put(n, new Series(n));
    }

    public int resolutionSeconds() { return res; }

    public synchronized void recordRequest(long epochSec, String endpoint, double ms, boolean failed, String status) {
        Series s = byEndpoint.get(endpoint);
        if (s == null) return; // not one of ours
        long bucket = Math.floorDiv(epochSec, (long) res) * res;
        if (bucket < flushedBefore) bucket = flushedBefore; // late sample: fold into the oldest open bucket
        s.record(bucket, epochSec, ms, failed, status);
        all.record(bucket, epochSec, ms, failed, null);
        if (bucket > maxBucket) {
            maxBucket = bucket;
            long cutoff = bucket - 2L * res;
            if (cutoff > flushedBefore) {
                flushedBefore = cutoff;
                for (Series x : byEndpoint.values()) x.flushBefore(cutoff, res);
                all.flushBefore(cutoff, res);
            }
        }
    }

    public synchronized void recordVus(long epochSec, int value) {
        long bucket = Math.floorDiv(epochSec, (long) res) * res;
        vus.merge(bucket, value, Math::max);
        if (bucket >= lastVusBucket) {
            lastVusBucket = bucket;
            lastVus = value;
        }
    }

    public synchronized LiveMetrics live() {
        long req = all.requests;
        double rate = req == 0 ? 0 : round4(all.errors / (double) req);
        double rps = all.closed.isEmpty() ? 0 : all.closed.getLast().rps();
        return new LiveMetrics(req, all.errors, rate, rps, round2(all.total.quantile(.95)), lastVus);
    }

    /** Closes every open bucket. Call once, after k6 has exited. */
    public synchronized void finish() {
        for (Series x : byEndpoint.values()) x.flushBefore(Long.MAX_VALUE, res);
        all.flushBefore(Long.MAX_VALUE, res);
        all.fillGaps(res);
    }

    public synchronized Timeline timeline() {
        Map<String, List<TimelinePoint>> series = new LinkedHashMap<>();
        series.put(PlanResolver.ALL_SERIES, List.copyOf(all.closed));
        byEndpoint.forEach((k, s) -> series.put(k, List.copyOf(s.closed)));
        List<VusPoint> v = new ArrayList<>(vus.size());
        vus.forEach((t, n) -> v.add(new VusPoint(t, n)));
        return new Timeline(res, v, series);
    }

    public synchronized Map<String, EndpointSummary> endpointSummaries() {
        Map<String, EndpointSummary> out = new LinkedHashMap<>();
        byEndpoint.forEach((k, s) -> out.put(k, s.summary()));
        return out;
    }

    public synchronized EndpointSummary overall() { return all.summary(); }

    // ------------------------------------------------------------------------------------------

    private static final class Bucket {
        long requests, errors;
        final LatencyHistogram hist = new LatencyHistogram();
    }

    private static final class Series {
        final String name;
        final LatencyHistogram total = new LatencyHistogram();
        long requests, errors, first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        final Map<String, long[]> statuses = new TreeMap<>();
        final TreeMap<Long, Bucket> open = new TreeMap<>();
        final List<TimelinePoint> closed = new ArrayList<>();

        Series(String name) { this.name = name; }

        void record(long bucket, long epochSec, double ms, boolean failed, String status) {
            Bucket b = open.computeIfAbsent(bucket, k -> new Bucket());
            b.requests++;
            b.hist.record(ms);
            requests++;
            total.record(ms);
            if (failed) { b.errors++; errors++; }
            if (epochSec < first) first = epochSec;
            if (epochSec > last) last = epochSec;
            if (status != null) statuses.computeIfAbsent(status, k -> new long[1])[0]++;
        }

        void flushBefore(long cutoff, int res) {
            while (!open.isEmpty() && open.firstKey() < cutoff) {
                Map.Entry<Long, Bucket> e = open.pollFirstEntry();
                Bucket b = e.getValue();
                LatencyHistogram h = b.hist;
                closed.add(new TimelinePoint(e.getKey(), b.requests, b.errors, round2(b.requests / (double) res),
                        round2(h.mean()), round2(h.quantile(.50)), round2(h.quantile(.95)),
                        round2(h.quantile(.99)), round2(h.max())));
            }
        }

        /** Inserts zero-request points so stalls (no responses at all) are visible on charts. */
        void fillGaps(int res) {
            if (closed.size() < 2) return;
            List<TimelinePoint> filled = new ArrayList<>(closed.size());
            long expected = closed.getFirst().t();
            for (TimelinePoint p : closed) {
                for (long t = expected; t < p.t(); t += res) filled.add(new TimelinePoint(t, 0, 0, 0, 0, 0, 0, 0, 0));
                filled.add(p);
                expected = p.t() + res;
            }
            closed.clear();
            closed.addAll(filled);
        }

        EndpointSummary summary() {
            Map<String, Long> codes = new LinkedHashMap<>();
            statuses.forEach((k, v) -> codes.put(k, v[0]));
            if (requests == 0) return new EndpointSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, codes);
            double active = Math.max(1, last - first + 1);
            return new EndpointSummary(requests, errors, round4(errors / (double) requests),
                    round2(requests / active), round2(total.mean()), round2(total.min()),
                    round2(total.quantile(.50)), round2(total.quantile(.90)), round2(total.quantile(.95)),
                    round2(total.quantile(.99)), round2(total.max()), codes);
        }
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
