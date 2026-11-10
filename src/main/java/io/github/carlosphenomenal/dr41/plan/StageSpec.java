package io.github.carlosphenomenal.dr41.plan;

/** Ramp to {@code target} virtual users over {@code duration} (e.g. "30s", "5m", "1h30m"). */
public record StageSpec(String duration, Integer target) {}
