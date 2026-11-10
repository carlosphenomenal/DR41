package io.github.carlosphenomenal.dr41.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue K6 k6,
        @DefaultValue Limits limits,
        @DefaultValue Storage storage,
        @DefaultValue Monitoring monitoring,
        @DefaultValue Safety safety) {

    public record K6(
            @DefaultValue("k6") String executable,
            @DefaultValue("0.45.0") String minVersion,
            @DefaultValue("5s") Duration versionCheckTimeout,
            @DefaultValue("60s") Duration hardTimeoutGrace,
            @DefaultValue("10s") Duration gracefulStop) {}

    public record Limits(
            @DefaultValue("1") int maxConcurrentTests,
            @DefaultValue("10") int maxQueuedTests,
            @DefaultValue("50") int maxEndpoints,
            @DefaultValue("50") int maxStagesPerEndpoint,
            @DefaultValue("5000") int maxVirtualUsers,
            @DefaultValue("6h") Duration maxTotalDuration,
            @DefaultValue("1000000") int maxBodyBytes,
            @DefaultValue("262144") int maxPlanBytes,
            @DefaultValue("1800") int maxTimelinePoints,
            @DefaultValue("4") int estimatedMemoryPerVuMb,
            @DefaultValue("0.8") double memoryHeadroomRatio) {}

    public record Storage(
            @DefaultValue("") String workDirectory,
            @DefaultValue("") String reportsDirectory,
            @DefaultValue("50") int maxFinishedRunsInMemory,
            @DefaultValue("7d") Duration reportRetention) {

        public Path workPath() {
            return workDirectory == null || workDirectory.isBlank()
                    ? Path.of(System.getProperty("java.io.tmpdir"), "DR41")
                    : Path.of(workDirectory);
        }

        public Path reportsPath() {
            return reportsDirectory == null || reportsDirectory.isBlank()
                    ? Path.of(System.getProperty("user.home"), ".dr41", "reports")
                    : Path.of(reportsDirectory);
        }
    }

    public record Monitoring(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("90") double saturationCpuPercent) {}

    public record Safety(
            List<String> allowedHosts,
            @DefaultValue("true") boolean preflightTargets,
            @DefaultValue("3s") Duration connectTimeout) {
        public Safety {
            allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        }
    }

    /** Defaults for unit tests (the Boot binder applies the same values from @DefaultValue). */
    public static AppProperties defaults() {
        return new AppProperties(
                new K6("k6", "0.45.0", Duration.ofSeconds(5), Duration.ofSeconds(60), Duration.ofSeconds(10)),
                new Limits(1, 10, 50, 50, 5000, Duration.ofHours(6), 1_000_000, 262_144, 1800, 4, 0.8),
                new Storage("", "", 50, Duration.ofDays(7)),
                new Monitoring(true, 90),
                new Safety(List.of(), true, Duration.ofSeconds(3)));
    }
}
