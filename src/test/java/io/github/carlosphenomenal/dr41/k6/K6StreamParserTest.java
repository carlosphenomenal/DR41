package io.github.carlosphenomenal.dr41.k6;

import io.github.carlosphenomenal.dr41.metrics.MetricsAggregator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class K6StreamParserTest {
    private static String point(String time, double v, String endpoint, String status, boolean ok) {
        return "{\"type\":\"Point\",\"data\":{\"time\":\"" + time + "\",\"value\":" + v + ",\"tags\":{\"endpoint\":\""
                + endpoint + "\",\"expected_response\":\"" + ok + "\",\"status\":\"" + status + "\"}},\"metric\":\"http_req_duration\"}";
    }

    @Test
    void aggregatesPerEndpointAndHandlesOffsets() {
        MetricsAggregator agg = new MetricsAggregator(List.of("a"), 1);
        K6StreamParser p = new K6StreamParser(agg);
        p.accept(point("2026-11-10T12:00:00.123456789+03:00", 10, "a", "200", true));
        p.accept(point("2026-11-10T09:00:00.9Z", 30, "a", "500", false));
        p.accept("{\"type\":\"Point\",\"data\":{\"time\":\"2026-11-10T09:00:00Z\",\"value\":1,\"tags\":{}},\"metric\":\"http_reqs\"}"); // ignored
        p.accept("garbage");                                                                                                         // ignored
        p.accept(point("2026-11-10T09:00:01Z", 5, "unknown", "200", true));                                                          // not ours
        agg.finish();
        var s = agg.overall();
        assertEquals(2, s.requests());
        assertEquals(1, s.errors());
        assertEquals(1, agg.timeline().series().get("__all__").size()); // both samples share the same second
        assertEquals(3, p.pointsParsed()); // 3 request lines parsed; the one for an unknown endpoint is dropped by the aggregator
        assertEquals(0, p.linesRejected());
    }

    @Test
    void worksWhenMetricFieldComesFirstAndIgnoresDefinitionLines() {
        MetricsAggregator agg = new MetricsAggregator(List.of("a"), 1);
        K6StreamParser p = new K6StreamParser(agg);
        p.accept("{\"type\":\"Metric\",\"data\":{\"name\":\"http_req_duration\",\"type\":\"trend\",\"contains\":\"time\"},\"metric\":\"http_req_duration\"}");
        p.accept("{\"metric\":\"http_req_duration\",\"type\":\"Point\",\"data\":{\"time\":\"2026-11-10T15:00:00.5+03:00\",\"value\":12.5,"
                + "\"tags\":{\"endpoint\":\"a\",\"expected_response\":\"true\",\"status\":\"200\"}}}");
        p.accept("{\"metric\":\"vus\",\"type\":\"Point\",\"data\":{\"time\":\"2026-11-10T15:00:00+03:00\",\"value\":1,\"tags\":{}}}");
        agg.finish();
        assertEquals(1, agg.overall().requests());
        assertEquals(0, p.linesRejected());
        assertEquals(1, agg.timeline().vus().size());
    }
}
