package io.github.carlosphenomenal.dr41.k6;

import io.github.carlosphenomenal.dr41.metrics.MetricsAggregator;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Allocation-light reader for k6's NDJSON output ({@code --out json=-}).
 * k6 emits ~15 metric lines per HTTP request; we only look at two of them and ignore the rest with a single
 * {@code endsWith} check, so the hot path does no JSON parsing at all for ~90% of lines.
 * <p>
 * Line shape (stable across k6 versions):
 * {"type":"Point","data":{"time":"2026-01-01T10:00:00.123456+02:00","value":12.5,"tags":{...}},"metric":"http_req_duration"}
 */
public final class K6StreamParser {
    private static final String METRIC_KEY = "\"metric\":\"";
    private static final String POINT_MARK = "\"type\":\"Point\"";

    private final MetricsAggregator sink;
    private long pointsParsed;
    private long linesRejected;
    private String firstPointSample;     // diagnostics: first data point k6 emitted, whatever its metric
    private String firstRejectedSample;  // diagnostics: first line we recognised but could not parse

    // timestamps within a run share the same "yyyy-MM-ddTHH:mm:ss" prefix for a whole second: cache the parse
    private String lastPrefix = "";
    private long lastLocalEpoch;
    private String lastOffset = "";
    private int lastOffsetSeconds;

    public K6StreamParser(MetricsAggregator sink) { this.sink = sink; }

    public long pointsParsed() { return pointsParsed; }
    public long linesRejected() { return linesRejected; }
    public String firstPointSample() { return firstPointSample; }
    public String firstRejectedSample() { return firstRejectedSample; }

    /** Field order independent: k6 versions differ in whether "metric" comes first or last on a line. */
    public void accept(String line) {
        if (!line.contains(POINT_MARK)) return;           // skips "Metric" definition lines and noise
        if (firstPointSample == null) firstPointSample = abbreviate(line);
        int m = line.indexOf(METRIC_KEY);
        if (m < 0) return;
        int s = m + METRIC_KEY.length();
        boolean duration = line.startsWith("http_req_duration\"", s);
        if (!duration && !line.startsWith("vus\"", s)) return;
        try {
            if (duration) request(line); else vus(line);
        } catch (RuntimeException e) {
            linesRejected++;
            if (firstRejectedSample == null) firstRejectedSample = abbreviate(line);
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 400 ? s : s.substring(0, 400) + "…";
    }

    private void request(String line) {
        int t = line.indexOf("\"time\":\"");
        if (t < 0) throw new IllegalArgumentException("no time");
        int tStart = t + 8;
        int tEnd = line.indexOf('"', tStart);
        long epoch = epochSecond(line, tStart, tEnd);
        double ms = number(line, tEnd);
        String endpoint = tag(line, "\"endpoint\":\"", tEnd);
        if (endpoint == null) throw new IllegalArgumentException("no endpoint tag");
        boolean failed = line.indexOf("\"expected_response\":\"false\"", tEnd) >= 0;
        String status = tag(line, "\"status\":\"", tEnd);
        sink.recordRequest(epoch, endpoint, ms, failed, status);
        pointsParsed++;
    }

    private void vus(String line) {
        int t = line.indexOf("\"time\":\"");
        if (t < 0) throw new IllegalArgumentException("no time");
        int tStart = t + 8;
        int tEnd = line.indexOf('"', tStart);
        sink.recordVus(epochSecond(line, tStart, tEnd), (int) Math.round(number(line, tEnd)));
    }

    private static double number(String line, int from) {
        int v = line.indexOf("\"value\":", from);
        if (v < 0) throw new IllegalArgumentException("no value");
        int s = v + 8;
        int e = s;
        while (e < line.length()) {
            char c = line.charAt(e);
            if (c == ',' || c == '}') break;
            e++;
        }
        return Double.parseDouble(line.substring(s, e));
    }

    private static String tag(String line, String key, int from) {
        int i = line.indexOf(key, from);
        if (i < 0) return null;
        int s = i + key.length();
        int e = line.indexOf('"', s);
        return e < 0 ? null : line.substring(s, e);
    }

    private long epochSecond(String s, int from, int to) {
        if (to - from < 20) throw new IllegalArgumentException("bad timestamp");
        int prefixEnd = from + 19;
        if (!s.regionMatches(from, lastPrefix, 0, 19) || lastPrefix.length() != 19) {
            lastPrefix = s.substring(from, prefixEnd);
            lastLocalEpoch = LocalDateTime.parse(lastPrefix).toEpochSecond(ZoneOffset.UTC);
        }
        int i = prefixEnd;
        if (i < to && s.charAt(i) == '.') {
            i++;
            while (i < to && Character.isDigit(s.charAt(i))) i++;
        }
        int len = to - i;
        if (len != lastOffset.length() || !s.regionMatches(i, lastOffset, 0, len)) {
            lastOffset = s.substring(i, to);
            lastOffsetSeconds = ZoneOffset.of(lastOffset).getTotalSeconds();
        }
        return lastLocalEpoch - lastOffsetSeconds;
    }
}
