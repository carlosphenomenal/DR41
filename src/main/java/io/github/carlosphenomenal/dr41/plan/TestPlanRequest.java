package io.github.carlosphenomenal.dr41.plan;

import java.util.List;
import java.util.Map;

/** The user-facing plan document (JSON or YAML). Enum-ish fields are strings so errors can be explained well. */
public record TestPlanRequest(
        String name,
        String testType,
        String mode,
        Integer virtualUsers,
        String duration,
        List<StageSpec> stages,
        Map<String, String> headers,
        Integer expectedStatus,
        ThresholdSpec thresholds,
        Boolean insecureSkipTlsVerify,
        Boolean abortOnThresholdFail,
        List<EndpointSpec> endpoints) {}
