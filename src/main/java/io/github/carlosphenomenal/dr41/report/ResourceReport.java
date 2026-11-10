package io.github.carlosphenomenal.dr41.report;

import java.util.List;

public record ResourceReport(boolean monitored, String note, int intervalSeconds,
                             ResourceSummary summary, List<ResourceSample> samples) {
    public static ResourceReport unavailable(String note) {
        return new ResourceReport(false, note, 0, null, List.of());
    }
}
