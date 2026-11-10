package io.github.carlosphenomenal.dr41.k6;

import io.github.carlosphenomenal.dr41.config.AppProperties;
import io.github.carlosphenomenal.dr41.plan.ResolvedEndpoint;
import io.github.carlosphenomenal.dr41.plan.ResolvedPlan;
import io.github.carlosphenomenal.dr41.plan.Stage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.*;

/**
 * Generates one k6 script for the whole plan: one scenario per endpoint (ramping-vus executor).
 * All user-controlled data is embedded through JSON serialisation of data structures, never by string
 * concatenation into code, so there is no script-injection surface.
 * <p>
 * Why these choices:
 *  - per-endpoint {@code endpoint} tag lets us split metrics per endpoint from the raw stream;
 *  - {@code responseCallback} makes k6 classify "failed" by the endpoint's own expected status;
 *  - {@code systemTags} is trimmed to what we use, which shrinks the metric stream considerably;
 *  - {@code handleSummary} returns nothing so no summary text pollutes the metric stream;
 *  - {@code discardResponseBodies} avoids buffering response bodies in memory.
 */
@Component
public class K6ScriptGenerator {
    private final ObjectMapper mapper;
    private final AppProperties props;

    public K6ScriptGenerator(ObjectMapper mapper, AppProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    public String generate(ResolvedPlan plan) {
        String grace = props.k6().gracefulStop().toSeconds() + "s";

        Map<String, Object> scenarios = new LinkedHashMap<>();
        Map<String, Object> thresholds = new LinkedHashMap<>();
        List<Map<String, Object>> targets = new ArrayList<>();
        StringBuilder exports = new StringBuilder();

        for (ResolvedEndpoint ep : plan.endpoints()) {
            Map<String, Object> sc = new LinkedHashMap<>();
            sc.put("executor", "ramping-vus");
            sc.put("exec", "ep" + ep.index());
            sc.put("startVUs", 0);
            sc.put("startTime", ep.startOffsetSeconds() + "s");
            sc.put("stages", ep.stages().stream().map(this::stage).toList());
            sc.put("gracefulRampDown", grace);
            sc.put("gracefulStop", grace);
            scenarios.put(ep.name(), sc);

            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", ep.name());
            t.put("method", ep.method());
            t.put("url", ep.url());
            t.put("headers", ep.headers());
            t.put("body", ep.body());
            t.put("timeout", ep.timeoutSeconds() + "s");
            t.put("expectedStatus", ep.expectedStatus());
            t.put("thinkTimeMs", ep.thinkTimeMs());
            targets.add(t);
            exports.append("export function ep").append(ep.index()).append("() { hit(").append(ep.index()).append("); }\n");

            List<Object> latency = new ArrayList<>();
            if (ep.p95Ms() != null) latency.add(threshold("p(95)<" + num(ep.p95Ms()), plan.abortOnThresholdFail()));
            if (ep.p99Ms() != null) latency.add(threshold("p(99)<" + num(ep.p99Ms()), plan.abortOnThresholdFail()));
            if (!latency.isEmpty()) thresholds.put("http_req_duration{endpoint:" + ep.name() + "}", latency);
            if (ep.errorRate() != null) {
                thresholds.put("http_req_failed{endpoint:" + ep.name() + "}",
                        List.of(threshold("rate<" + num(ep.errorRate()), plan.abortOnThresholdFail())));
            }
        }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("discardResponseBodies", true);
        options.put("insecureSkipTLSVerify", plan.insecureSkipTlsVerify());
        options.put("userAgent", "DR41-LoadTester/1.0 (k6)");
        options.put("systemTags", List.of("status", "expected_response", "error_code"));
        options.put("scenarios", scenarios);
        if (!thresholds.isEmpty()) options.put("thresholds", thresholds);

        return """
                import http from 'k6/http';
                import { sleep } from 'k6';

                export const options = %s;

                const targets = %s;
                const params = targets.map((t) => ({
                  headers: t.headers,
                  timeout: t.timeout,
                  tags: { endpoint: t.name },
                  responseCallback: http.expectedStatuses(t.expectedStatus),
                }));

                function hit(i) {
                  const t = targets[i];
                  http.request(t.method, t.url, t.body, params[i]);
                  if (t.thinkTimeMs > 0) {
                    sleep(t.thinkTimeMs / 1000);
                  }
                }

                // Returning nothing suppresses k6's end-of-test summary, keeping stdout a pure metric stream.
                // (Replaces the --no-summary CLI flag, which newer k6 versions removed.)
                export function handleSummary() {
                  return {};
                }

                %s""".formatted(mapper.writeValueAsString(options), mapper.writeValueAsString(targets), exports);
    }

    private Map<String, Object> stage(Stage s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("duration", s.durationSeconds() + "s");
        m.put("target", s.target());
        return m;
    }

    private static Object threshold(String expression, boolean abortOnFail) {
        if (!abortOnFail) return expression;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("threshold", expression);
        m.put("abortOnFail", true);
        m.put("delayAbortEval", "10s");
        return m;
    }

    private static String num(double d) {
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }
}
