package io.github.carlosphenomenal.dr41.report;

public record HardwareInfo(String os, String cpuModel, int physicalCores, int logicalCores,
                           long totalMemoryBytes, long availableMemoryBytes, long jvmMaxHeapBytes) {}
