package io.github.carlosphenomenal.dr41.plan;

/** Pass/fail criteria. errorRate is a fraction (0.01 = 1%). */
public record ThresholdSpec(Double p95Ms, Double p99Ms, Double errorRate) {}
