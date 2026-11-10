package io.github.carlosphenomenal.dr41.plan;

import java.util.List;
import java.util.Map;

/**
 * One endpoint under test. Anything left null inherits from the plan-level defaults.
 * {@code body} may be a string, or a YAML/JSON object/array that is serialised as JSON.
 */
public record EndpointSpec(
        String name,
        String url,
        String method,
        Map<String, String> headers,
        Object body,
        String testType,
        Integer virtualUsers,
        String duration,
        List<StageSpec> stages,
        Integer expectedStatus,
        Integer thinkTimeMs,
        String timeout,
        ThresholdSpec thresholds) {}
