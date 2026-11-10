package io.github.carlosphenomenal.dr41.metrics;

/**
 * Fixed-memory latency histogram with geometric buckets (3% relative error, 0.01 ms .. 1 h).
 * Percentiles over millions of requests cost ~2.7 KB instead of one double per request.
 * Not thread-safe; callers synchronise.
 */
public final class LatencyHistogram {
    private static final double MIN_MS = 0.01;
    private static final double MAX_MS = 3_600_000.0;
    private static final double GROWTH = 1.03;
    private static final double LOG_GROWTH = Math.log(GROWTH);
    private static final int BUCKETS = (int) Math.ceil(Math.log(MAX_MS / MIN_MS) / LOG_GROWTH) + 1;

    private int[] counts;            // allocated lazily: empty histograms are free
    private long total;
    private double sum;
    private double min = Double.POSITIVE_INFINITY;
    private double max;

    public void record(double ms) {
        if (!(ms >= 0) || Double.isInfinite(ms)) return; // NaN, negative, infinite: ignore
        if (counts == null) counts = new int[BUCKETS];
        counts[indexOf(ms)]++;
        total++;
        sum += ms;
        if (ms < min) min = ms;
        if (ms > max) max = ms;
    }

    public void merge(LatencyHistogram o) {
        if (o.total == 0) return;
        if (counts == null) counts = new int[BUCKETS];
        for (int i = 0; i < BUCKETS; i++) counts[i] += o.counts[i];
        total += o.total;
        sum += o.sum;
        min = Math.min(min, o.min);
        max = Math.max(max, o.max);
    }

    public long count() { return total; }
    public double mean() { return total == 0 ? 0 : sum / total; }
    public double min() { return total == 0 ? 0 : min; }
    public double max() { return total == 0 ? 0 : max; }

    /** @param q quantile in [0,1] */
    public double quantile(double q) {
        if (total == 0) return 0;
        if (q <= 0) return min;
        if (q >= 1) return max;
        long rank = Math.max(1, (long) Math.ceil(q * total));
        long cum = 0;
        for (int i = 0; i < BUCKETS; i++) {
            cum += counts[i];
            if (cum >= rank) return Math.clamp(min, representative(i), max);
        }
        return max;
    }

    static int indexOf(double ms) {
        if (ms <= MIN_MS) return 0;
        int i = (int) (Math.log(ms / MIN_MS) / LOG_GROWTH) + 1;
        return Math.min(i, BUCKETS - 1);
    }

    private static double representative(int i) {
        if (i == 0) return MIN_MS / 2;
        double lower = MIN_MS * Math.pow(GROWTH, i - 1);
        return lower * Math.sqrt(GROWTH); // geometric midpoint of the bucket
    }
}
