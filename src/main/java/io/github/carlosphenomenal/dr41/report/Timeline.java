package io.github.carlosphenomenal.dr41.report;

import java.util.List;
import java.util.Map;

/** Graph-ready data. {@code series} is keyed by endpoint name plus "__all__" for the aggregate. */
public record Timeline(int resolutionSeconds, List<VusPoint> vus, Map<String, List<TimelinePoint>> series) {}
