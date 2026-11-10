package io.github.carlosphenomenal.dr41.report;

/** Host-wide and per-process usage. CPU values are % of total machine capacity (all cores = 100). */
public record ResourceSample(long t, double cpuPercent, long memoryUsedBytes, double memoryUsedPercent,
                             double k6CpuPercent, long k6RssBytes, double appCpuPercent, long appRssBytes) {}
