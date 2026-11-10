package io.github.carlosphenomenal.dr41.plan;

import io.github.carlosphenomenal.dr41.config.AppProperties;
import io.github.carlosphenomenal.dr41.error.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanResolverTest {
    private final PlanResolver resolver = new PlanResolver(AppProperties.defaults(), JsonMapper.builder().build());

    private static EndpointSpec ep(String name, String url) {
        return new EndpointSpec(name, url, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static TestPlanRequest plan(String type, Integer vus, String dur, EndpointSpec... eps) {
        return new TestPlanRequest("p", type, null, vus, dur, null, null, null, null, null, null, List.of(eps));
    }

    @Test
    void loadPresetSumsToRequestedDuration() {
        ResolvedPlan p = resolver.resolve(plan("LOAD", 50, "5m", ep("a", "http://localhost:8080/x")));
        var stages = p.endpoints().getFirst().stages();
        assertEquals(300, stages.stream().mapToInt(Stage::durationSeconds).sum());
        assertEquals(50, p.peakVus());
    }

    @Test
    void allPresetsSumExactly() {
        for (TestType t : List.of(TestType.LOAD, TestType.STRESS, TestType.SPIKE, TestType.SOAK, TestType.SMOKE)) {
            int total = 97;
            int sum = LoadProfiles.preset(t, 10, total).stream().mapToInt(Stage::durationSeconds).sum();
            assertEquals(total, sum, t.name());
        }
    }

    @Test
    void reportsAllProblemsAtOnce() {
        ApiException ex = assertThrows(ApiException.class, () -> resolver.resolve(plan("LOAD", 10, "1m",
                ep("bad name!", "ftp://x"), ep("ok", "http://user:pw@host/"))));
        assertTrue(ex.getDetails().size() >= 3, ex.getDetails().toString());
    }

    @Test
    void rejectsDuplicateNamesAndGetWithBody() {
        EndpointSpec getWithBody = new EndpointSpec("b", "http://h/", "GET", null, "x", null, null, null, null, null, null, null, null);
        ApiException ex = assertThrows(ApiException.class, () -> resolver.resolve(plan("LOAD", 10, "1m",
                ep("a", "http://h/"), ep("a", "http://h/"), getWithBody)));
        assertTrue(ex.getDetails().stream().anyMatch(s -> s.contains("duplicate")));
        assertTrue(ex.getDetails().stream().anyMatch(s -> s.contains("must not have a body")));
    }

    @Test
    void rejectsHeaderInjection() {
        EndpointSpec e = new EndpointSpec("a", "http://h/", null, Map.of("X-A", "v\r\nEvil: 1"), null, null, null, null, null, null, null, null, null);
        assertThrows(ApiException.class, () -> resolver.resolve(plan("LOAD", 10, "1m", e)));
    }

    @Test
    void sequentialModeOffsetsEndpoints() {
        TestPlanRequest r = new TestPlanRequest("p", "LOAD", "SEQUENTIAL", 10, "1m", null, null, null, null, null, null,
                List.of(ep("a", "http://h/a"), ep("b", "http://h/b")));
        ResolvedPlan p = resolver.resolve(r);
        assertEquals(0, p.endpoints().get(0).startOffsetSeconds());
        assertEquals(70, p.endpoints().get(1).startOffsetSeconds()); // 60s + 10s graceful stop
        assertEquals(10, p.peakVus());
    }

    @Test
    void parallelPeaksAddUpAndAreCapped() {
        assertThrows(ApiException.class, () -> resolver.resolve(plan("LOAD", 4000, "1m",
                ep("a", "http://h/a"), ep("b", "http://h/b"))));
    }
}
