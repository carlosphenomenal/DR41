package io.github.carlosphenomenal.dr41.report;

import io.github.carlosphenomenal.dr41.plan.ResolvedEndpoint;
import io.github.carlosphenomenal.dr41.plan.Stage;

import java.util.List;

/** Secret-free description of what was (or will be) executed: header values, bodies and URL queries are never exposed. */
public record PlanView(int totalSeconds, int peakVus, boolean abortOnThresholdFail, List<EndpointView> endpoints) {

    public record EndpointView(String name, String url, String method, List<String> headerNames, boolean hasBody,
                               int expectedStatus, int thinkTimeMs, int timeoutSeconds,
                               Double p95Ms, Double p99Ms, Double errorRate,
                               List<Stage> stages, int startOffsetSeconds) {

        public static EndpointView of(ResolvedEndpoint e) {
            return new EndpointView(e.name(), redact(e.url()), e.method(), List.copyOf(e.headers().keySet()),
                    e.body() != null, e.expectedStatus(), e.thinkTimeMs(), e.timeoutSeconds(),
                    e.p95Ms(), e.p99Ms(), e.errorRate(), e.stages(), e.startOffsetSeconds());
        }

        private static String redact(String url) {
            int cut = url.length();
            int q = url.indexOf('?');
            int f = url.indexOf('#');
            if (q >= 0) cut = q;
            if (f >= 0) cut = Math.min(cut, f);
            return cut == url.length() ? url : url.substring(0, cut) + (q >= 0 ? "?…" : "");
        }
    }
}
