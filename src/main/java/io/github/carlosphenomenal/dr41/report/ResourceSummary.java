package io.github.carlosphenomenal.dr41.report;

public record ResourceSummary(double avgCpuPercent, double peakCpuPercent,
                              double avgMemoryPercent, double peakMemoryPercent, long peakMemoryUsedBytes,
                              double peakK6CpuPercent, long peakK6RssBytes,
                              double peakAppCpuPercent, long peakAppRssBytes,
                              boolean loadGeneratorSaturated) {}
