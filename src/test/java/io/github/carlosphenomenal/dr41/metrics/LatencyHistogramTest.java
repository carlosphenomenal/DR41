package io.github.carlosphenomenal.dr41.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LatencyHistogramTest {
    @Test
    void percentilesAreWithinBucketError() {
        LatencyHistogram h = new LatencyHistogram();
        for (int i = 1; i <= 10_000; i++) h.record(i / 10.0); // 0.1 .. 1000 ms
        assertEquals(500, h.quantile(.50), 500 * 0.04);
        assertEquals(950, h.quantile(.95), 950 * 0.04);
        assertEquals(1000, h.max(), 0.001);
        assertEquals(10_000, h.count());
    }

    @Test
    void ignoresGarbageAndHandlesEmpty() {
        LatencyHistogram h = new LatencyHistogram();
        assertEquals(0, h.quantile(.99));
        h.record(Double.NaN);
        h.record(-5);
        h.record(Double.POSITIVE_INFINITY);
        assertEquals(0, h.count());
    }

    @Test
    void mergeCombinesCounts() {
        LatencyHistogram a = new LatencyHistogram();
        LatencyHistogram b = new LatencyHistogram();
        a.record(10);
        b.record(20);
        a.merge(b);
        assertEquals(2, a.count());
        assertEquals(15, a.mean(), 0.001);
    }
}
