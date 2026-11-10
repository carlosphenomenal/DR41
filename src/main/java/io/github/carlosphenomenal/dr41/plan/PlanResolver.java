package io.github.carlosphenomenal.dr41.plan;

import io.github.carlosphenomenal.dr41.config.AppProperties;
import io.github.carlosphenomenal.dr41.error.ApiException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Validates a {@link TestPlanRequest} and resolves it into concrete, safe, bounded stages.
 * Collects <em>every</em> problem (not just the first) with a path such as {@code endpoints[2].url}.
 */
@Component
public class PlanResolver {
    public static final String ALL_SERIES = "__all__";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,256}");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private final AppProperties props;
    private final ObjectMapper mapper;

    public PlanResolver(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public ResolvedPlan resolve(TestPlanRequest r) {
        if (r == null) throw ApiException.invalidPlan(List.of("The request body is empty"));
        AppProperties.Limits lim = props.limits();
        List<String> v = new ArrayList<>();
        List<String> w = new ArrayList<>();

        String name = r.name() == null || r.name().isBlank()
                ? "test-" + Instant.now().truncatedTo(ChronoUnit.SECONDS).toString().replace(':', '-')
                : r.name().trim();
        if (name.length() > 100 || name.chars().anyMatch(Character::isISOControl)) {
            v.add("name: must be at most 100 characters and contain no control characters");
        }
        TestType planType = parseEnum(TestType.class, r.testType(), "testType", v);
        ExecutionMode mode = Optional.ofNullable(parseEnum(ExecutionMode.class, r.mode(), "mode", v))
                .orElse(ExecutionMode.PARALLEL);
        validateHeaders(r.headers(), "headers", v);
        validateThresholds(r.thresholds(), "thresholds", v);
        if (r.expectedStatus() != null && (r.expectedStatus() < 100 || r.expectedStatus() > 599)) {
            v.add("expectedStatus: must be between 100 and 599");
        }

        List<ResolvedEndpoint> endpoints = new ArrayList<>();
        List<EndpointSpec> specs = r.endpoints();
        if (specs == null || specs.isEmpty()) {
            v.add("endpoints: at least one endpoint is required");
        } else if (specs.size() > lim.maxEndpoints()) {
            v.add("endpoints: " + specs.size() + " endpoints exceeds the limit of " + lim.maxEndpoints());
        } else {
            Set<String> names = new HashSet<>();
            for (int i = 0; i < specs.size(); i++) {
                ResolvedEndpoint ep = endpoint(i, specs.get(i), r, planType, names, v, w);
                if (ep != null) endpoints.add(ep);
            }
        }
        if (!v.isEmpty()) throw ApiException.invalidPlan(v);

        // Scheduling: sequential endpoints start after the previous one has fully stopped.
        int gap = (int) props.k6().gracefulStop().toSeconds();
        List<ResolvedEndpoint> scheduled = new ArrayList<>();
        int offset = 0;
        int total = 0;
        int peak = 0;
        for (ResolvedEndpoint ep : endpoints) {
            if (mode == ExecutionMode.SEQUENTIAL) {
                scheduled.add(ep.withStartOffset(offset));
                offset += ep.durationSeconds() + gap;
                total = offset - gap;
                peak = Math.max(peak, ep.peakVus());
            } else {
                scheduled.add(ep);
                total = Math.max(total, ep.durationSeconds());
                peak += ep.peakVus();
            }
        }
        if (peak > lim.maxVirtualUsers()) {
            v.add("Peak concurrent virtual users (" + peak + ") exceeds the configured limit of " + lim.maxVirtualUsers()
                    + (mode == ExecutionMode.PARALLEL && endpoints.size() > 1
                    ? ". In PARALLEL mode the peaks of all endpoints add up; use mode: SEQUENTIAL or lower them." : ""));
        }
        if (total > lim.maxTotalDuration().toSeconds()) {
            v.add("Total test duration (" + total + "s) exceeds the configured limit of " + lim.maxTotalDuration().toSeconds() + "s");
        }
        if (!v.isEmpty()) throw ApiException.invalidPlan(v);

        w.add("Estimated run time: " + total + "s plus k6 start-up and up to " + gap + "s graceful stop.");
        return new ResolvedPlan(name, planType == null ? TestType.LOAD : planType, mode,
                Boolean.TRUE.equals(r.insecureSkipTlsVerify()), Boolean.TRUE.equals(r.abortOnThresholdFail()),
                List.copyOf(scheduled), List.copyOf(new LinkedHashSet<>(w)), total, peak);
    }

    // ---------------------------------------------------------------- endpoint

    private ResolvedEndpoint endpoint(int i, EndpointSpec e, TestPlanRequest p, TestType planType,
                                      Set<String> names, List<String> v, List<String> w) {
        String path = "endpoints[" + i + "]";
        if (e == null) {
            v.add(path + ": must not be null");
            return null;
        }
        int before = v.size();

        String name = e.name() == null || e.name().isBlank() ? "endpoint-" + (i + 1) : e.name().trim();
        if (!NAME.matcher(name).matches()) {
            v.add(path + ".name: must match [A-Za-z0-9_-]{1,64}, was '" + abbreviate(name) + "'");
        } else if (ALL_SERIES.equals(name)) {
            v.add(path + ".name: '" + ALL_SERIES + "' is reserved");
        } else if (!names.add(name)) {
            v.add(path + ".name: duplicate endpoint name '" + name + "'");
        }

        String url = url(e.url(), path + ".url", v);

        String method = e.method() == null || e.method().isBlank() ? "GET" : e.method().trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) v.add(path + ".method: '" + abbreviate(method) + "' is not one of " + new TreeSet<>(METHODS));

        validateHeaders(e.headers(), path + ".headers", v);
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (p.headers() != null) p.headers().forEach((k, val) -> { if (k != null && val != null) headers.put(k, val); });
        if (e.headers() != null) e.headers().forEach((k, val) -> { if (k != null && val != null) headers.put(k, val); });

        String body = body(e.body(), method, headers, path, v, w);

        int expected = e.expectedStatus() != null ? e.expectedStatus() : p.expectedStatus() != null ? p.expectedStatus() : 200;
        if (expected < 100 || expected > 599) v.add(path + ".expectedStatus: must be between 100 and 599");

        int think = e.thinkTimeMs() == null ? 0 : e.thinkTimeMs();
        if (think < 0 || think > 600_000) v.add(path + ".thinkTimeMs: must be between 0 and 600000");

        int timeout = 30;
        if (e.timeout() != null) {
            try {
                long s = DurationParser.parseSeconds(e.timeout());
                if (s < 1 || s > 600) v.add(path + ".timeout: must be between 1s and 10m");
                else timeout = (int) s;
            } catch (IllegalArgumentException ex) {
                v.add(path + ".timeout: " + ex.getMessage());
            }
        }

        validateThresholds(e.thresholds(), path + ".thresholds", v);
        ThresholdSpec pt = p.thresholds();
        ThresholdSpec et = e.thresholds();
        Double p95 = et != null && et.p95Ms() != null ? et.p95Ms() : pt == null ? null : pt.p95Ms();
        Double p99 = et != null && et.p99Ms() != null ? et.p99Ms() : pt == null ? null : pt.p99Ms();
        Double err = et != null && et.errorRate() != null ? et.errorRate() : pt == null ? null : pt.errorRate();

        List<Stage> stages = stages(e, p, planType, path, v, w);

        if (think == 0 && stages != null && stages.stream().mapToInt(Stage::target).max().orElse(0) >= 200) {
            w.add(path + ": no thinkTimeMs with high concurrency. Virtual users fire back-to-back, so the request "
                    + "rate is governed by server latency (fine for stress tests, misleading for realistic load).");
        }
        if (v.size() > before || stages == null) return null;
        return new ResolvedEndpoint(i, name, url, method, headers, body, expected, think, timeout, p95, p99, err, stages, 0);
    }

    private String url(String raw, String path, List<String> v) {
        if (raw == null || raw.isBlank()) {
            v.add(path + ": required");
            return null;
        }
        String s = raw.trim();
        if (s.length() > 2048) {
            v.add(path + ": longer than 2048 characters");
            return null;
        }
        URI u;
        try {
            u = new URI(s);
        } catch (URISyntaxException ex) {
            v.add(path + ": not a valid URL (" + ex.getReason() + "). Percent-encode spaces and special characters.");
            return null;
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            v.add(path + ": scheme must be http or https");
            return null;
        }
        if (u.getHost() == null || u.getHost().isBlank()) {
            v.add(path + ": URL has no host");
            return null;
        }
        if (u.getUserInfo() != null) {
            v.add(path + ": credentials in the URL are not allowed (they would leak into reports). Use an Authorization header.");
            return null;
        }
        if (u.getPort() > 65535) {
            v.add(path + ": port out of range");
            return null;
        }
        List<String> allowed = props.safety().allowedHosts();
        if (!allowed.isEmpty()) {
            String host = u.getHost().toLowerCase(Locale.ROOT);
            boolean ok = allowed.stream().map(a -> a.toLowerCase(Locale.ROOT))
                    .anyMatch(a -> host.equals(a) || host.endsWith("." + a));
            if (!ok) {
                v.add(path + ": host '" + host + "' is not in app.safety.allowed-hosts");
                return null;
            }
        }
        return s;
    }

    private String body(Object raw, String method, Map<String, String> headers, String path, List<String> v, List<String> w) {
        if (raw == null) return null;
        String s;
        boolean structured = !(raw instanceof CharSequence);
        if (structured) {
            try {
                s = mapper.writeValueAsString(raw);
            } catch (RuntimeException ex) {
                v.add(path + ".body: cannot be serialised as JSON");
                return null;
            }
            headers.putIfAbsent("Content-Type", "application/json");
        } else {
            s = raw.toString();
        }
        if (s.isEmpty()) return null;
        if (s.getBytes(StandardCharsets.UTF_8).length > props.limits().maxBodyBytes()) {
            v.add(path + ".body: larger than " + props.limits().maxBodyBytes() + " bytes");
            return null;
        }
        if (method.equals("GET") || method.equals("HEAD")) {
            v.add(path + ".body: " + method + " requests must not have a body");
            return null;
        }
        if (!structured && !headers.containsKey("Content-Type")) {
            w.add(path + ": a body is set without a Content-Type header; the server may reject or misparse it.");
        }
        return s;
    }

    // ------------------------------------------------------------------ stages

    private List<Stage> stages(EndpointSpec e, TestPlanRequest p, TestType planType, String path, List<String> v, List<String> w) {
        AppProperties.Limits lim = props.limits();
        boolean overrides = e.testType() != null || e.virtualUsers() != null || e.duration() != null;
        List<StageSpec> custom = e.stages() != null ? e.stages() : (overrides ? null : p.stages());
        String sp = e.stages() != null ? path + ".stages" : "stages";

        if (custom != null) {
            if (custom.isEmpty()) { v.add(sp + ": must contain at least one stage"); return null; }
            if (custom.size() > lim.maxStagesPerEndpoint()) {
                v.add(sp + ": " + custom.size() + " stages exceeds the limit of " + lim.maxStagesPerEndpoint());
                return null;
            }
            List<Stage> out = new ArrayList<>();
            long total = 0;
            int before = v.size();
            for (int k = 0; k < custom.size(); k++) {
                StageSpec s = custom.get(k);
                String stp = sp + "[" + k + "]";
                if (s == null || s.target() == null) { v.add(stp + ": 'duration' and 'target' are required"); continue; }
                if (s.target() < 0 || s.target() > lim.maxVirtualUsers()) {
                    v.add(stp + ".target: must be between 0 and " + lim.maxVirtualUsers());
                    continue;
                }
                try {
                    long d = DurationParser.parseSeconds(s.duration());
                    if (d > Integer.MAX_VALUE) throw new IllegalArgumentException("duration too large");
                    total += d;
                    out.add(new Stage((int) d, s.target()));
                } catch (IllegalArgumentException ex) {
                    v.add(stp + ".duration: " + ex.getMessage());
                }
            }
            if (v.size() > before) return null;
            if (out.stream().mapToInt(Stage::target).max().orElse(0) == 0) { v.add(sp + ": at least one stage needs a target above 0"); return null; }
            if (total < 1) { v.add(sp + ": total duration must be at least 1s"); return null; }
            if (total > lim.maxTotalDuration().toSeconds()) { v.add(sp + ": total duration exceeds " + lim.maxTotalDuration().toSeconds() + "s"); return null; }
            return out;
        }

        TestType type = parseEnum(TestType.class, e.testType(), path + ".testType", v);
        if (type == null) type = planType == null ? TestType.LOAD : planType;
        if (type == TestType.CUSTOM) { v.add(path + ": testType CUSTOM requires 'stages'"); return null; }

        Integer vus = e.virtualUsers() != null ? e.virtualUsers() : p.virtualUsers();
        String dur = e.duration() != null ? e.duration() : p.duration();
        if (type == TestType.SMOKE) {
            if (vus == null) vus = 1;
            if (dur == null) dur = "30s";
        }
        if (vus == null) { v.add(path + ": 'virtualUsers' is required for testType " + type + " (here or at plan level)"); return null; }
        if (dur == null) { v.add(path + ": 'duration' is required for testType " + type + " (here or at plan level)"); return null; }
        if (vus < 1 || vus > lim.maxVirtualUsers()) { v.add(path + ".virtualUsers: must be between 1 and " + lim.maxVirtualUsers()); return null; }
        long seconds;
        try {
            seconds = DurationParser.parseSeconds(dur);
        } catch (IllegalArgumentException ex) {
            v.add(path + ".duration: " + ex.getMessage());
            return null;
        }
        long min = type == TestType.SMOKE ? 1 : 10;
        if (seconds < min) { v.add(path + ".duration: " + type + " tests need at least " + min + "s"); return null; }
        if (seconds > lim.maxTotalDuration().toSeconds()) { v.add(path + ".duration: exceeds " + lim.maxTotalDuration().toSeconds() + "s"); return null; }
        if (type == TestType.SMOKE && vus > 5) w.add(path + ": a SMOKE test normally uses 1-5 VUs.");
        return LoadProfiles.preset(type, vus, (int) seconds);
    }

    // ----------------------------------------------------------------- helpers

    private void validateHeaders(Map<String, String> h, String path, List<String> v) {
        if (h == null) return;
        if (h.size() > 100) v.add(path + ": more than 100 headers");
        h.forEach((k, val) -> {
            if (k == null || !HEADER_NAME.matcher(k).matches()) {
                v.add(path + ": invalid header name '" + abbreviate(String.valueOf(k)) + "'");
            } else if (val == null || val.length() > 8192 || val.chars().anyMatch(c -> c == '\r' || c == '\n' || c == 0)) {
                v.add(path + "." + k + ": value must be at most 8192 characters and contain no line breaks or NUL");
            }
        });
    }

    private void validateThresholds(ThresholdSpec t, String path, List<String> v) {
        if (t == null) return;
        if (t.p95Ms() != null && !(t.p95Ms() > 0)) v.add(path + ".p95Ms: must be greater than 0");
        if (t.p99Ms() != null && !(t.p99Ms() > 0)) v.add(path + ".p99Ms: must be greater than 0");
        if (t.errorRate() != null && !(t.errorRate() >= 0 && t.errorRate() <= 1)) {
            v.add(path + ".errorRate: must be a fraction between 0 and 1 (0.01 = 1%)");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String path, List<String> v) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            v.add(path + ": '" + abbreviate(raw) + "' is not valid; expected one of " + Arrays.toString(type.getEnumConstants()));
            return null;
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }
}
