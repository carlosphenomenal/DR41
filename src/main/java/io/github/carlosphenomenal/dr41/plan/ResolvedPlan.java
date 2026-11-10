package io.github.carlosphenomenal.dr41.plan;

import io.github.carlosphenomenal.dr41.report.PlanView;

import java.util.List;

public record ResolvedPlan(
        String name,
        TestType type,
        ExecutionMode mode,
        boolean insecureSkipTlsVerify,
        boolean abortOnThresholdFail,
        List<ResolvedEndpoint> endpoints,
        List<String> warnings,
        int totalSeconds,
        int peakVus) {

    /** Secret-free projection for API responses and persisted reports. */
    public PlanView toView() {
        return new PlanView(totalSeconds, peakVus, abortOnThresholdFail,
                endpoints.stream().map(PlanView.EndpointView::of).toList());
    }
}
