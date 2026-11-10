package io.github.carlosphenomenal.dr41.monitor;

import io.github.carlosphenomenal.dr41.config.AppProperties;
import io.github.carlosphenomenal.dr41.report.HardwareInfo;
import io.github.carlosphenomenal.dr41.report.ResourceReport;
import io.github.carlosphenomenal.dr41.report.ResourceSample;
import io.github.carlosphenomenal.dr41.report.ResourceSummary;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Samples host CPU/memory plus k6's and this app's own process (via OSHI) while a test runs.
 * Important caveat for interpreting results: this measures the machine generating the load. If the target
 * runs elsewhere, its CPU/memory are NOT visible here. We use the same data to detect when the load generator
 * itself is saturated, which invalidates latency numbers.
 * OSHI relies on native access; if it cannot initialise on this platform the app still works, just without samples.
 */
@Component
public class ResourceMonitor {
    private static final Logger log = LoggerFactory.getLogger(ResourceMonitor.class);

    private final AppProperties props;
    private final ScheduledExecutorService scheduler;
    private final int maxSamples;
    private CentralProcessor cpu;
    private GlobalMemory memory;
    private OperatingSystem os;
    private int logicalCpus = Runtime.getRuntime().availableProcessors();
    private String cpuModel = "unknown";
    private int physicalCpus = logicalCpus;
    private volatile boolean available;

    public ResourceMonitor(AppProperties props, @Qualifier("housekeepingScheduler") ScheduledExecutorService scheduler) {
        this.props = props;
        this.scheduler = scheduler;
        this.maxSamples = props.limits().maxTimelinePoints() * 2;
        if (!props.monitoring().enabled()) {
            log.info("Resource monitoring disabled by configuration");
            return;
        }
        try {
            SystemInfo si = new SystemInfo();
            this.cpu = si.getHardware().getProcessor();
            this.memory = si.getHardware().getMemory();
            this.os = si.getOperatingSystem();
            this.logicalCpus = Math.max(1, cpu.getLogicalProcessorCount());
            this.physicalCpus = Math.max(1, cpu.getPhysicalProcessorCount());
            this.cpuModel = cpu.getProcessorIdentifier().getName();
            this.available = true;
        } catch (Throwable t) { // includes UnsatisfiedLinkError on exotic platforms
            log.warn("OSHI could not initialise; resource metrics are disabled: {}", t.toString());
        }
    }

    public boolean available() { return available; }

    public HardwareInfo hardware() {
        long total = available ? memory.getTotal() : -1;
        long avail = available ? memory.getAvailable() : -1;
        return new HardwareInfo(System.getProperty("os.name") + " " + System.getProperty("os.version"),
                cpuModel, physicalCpus, logicalCpus, total, avail, Runtime.getRuntime().maxMemory());
    }

    /** Bytes of memory the OS says can be allocated without swapping, or -1 if unknown. */
    public long availableMemoryBytes() { return available ? memory.getAvailable() : -1; }

    public int logicalCpus() { return logicalCpus; }

    public Session start(int intervalSeconds, LongSupplier k6Pid) {
        Session s = new Session(Math.max(1, intervalSeconds), k6Pid);
        if (available) s.begin();
        return s;
    }

    public final class Session {
        private final int interval;
        private final LongSupplier k6Pid;
        private final List<ResourceSample> samples = new ArrayList<>();
        private volatile ResourceSample latest;
        private long[] prevTicks;
        private OSProcess prevK6;
        private OSProcess prevSelf;
        private ScheduledFuture<?> future;

        private Session(int interval, LongSupplier k6Pid) {
            this.interval = interval;
            this.k6Pid = k6Pid;
        }

        private void begin() {
            try {
                prevTicks = cpu.getSystemCpuLoadTicks();
                prevSelf = os.getProcess(os.getProcessId());
                future = scheduler.scheduleAtFixedRate(this::tick, interval, interval, TimeUnit.SECONDS);
            } catch (Throwable t) {
                log.warn("Could not start resource sampling: {}", t.toString());
            }
        }

        public ResourceSample latest() { return latest; }

        private void tick() {
            try { // an exception escaping a scheduled task would silently cancel all future ticks
                double sys = pct(cpu.getSystemCpuLoadBetweenTicks(prevTicks) * 100.0);
                prevTicks = cpu.getSystemCpuLoadTicks();
                long total = memory.getTotal();
                long used = total - memory.getAvailable();

                double k6Cpu = 0;
                long k6Rss = 0;
                long pid = k6Pid.getAsLong();
                if (pid > 0) {
                    OSProcess p = os.getProcess((int) pid);
                    if (p != null) {
                        if (prevK6 != null && prevK6.getProcessID() == p.getProcessID()) {
                            k6Cpu = pct(p.getProcessCpuLoadBetweenTicks(prevK6) * 100.0 / logicalCpus);
                        }
                        k6Rss = p.getResidentSetSize();
                        prevK6 = p;
                    }
                }
                double appCpu = 0;
                long appRss = 0;
                OSProcess self = os.getProcess(os.getProcessId());
                if (self != null) {
                    if (prevSelf != null) appCpu = pct(self.getProcessCpuLoadBetweenTicks(prevSelf) * 100.0 / logicalCpus);
                    appRss = self.getResidentSetSize();
                    prevSelf = self;
                }
                ResourceSample s = new ResourceSample(System.currentTimeMillis() / 1000, sys, used,
                        total == 0 ? 0 : pct(used * 100.0 / total), k6Cpu, k6Rss, appCpu, appRss);
                latest = s;
                synchronized (samples) {
                    if (samples.size() < maxSamples) samples.add(s); // hard cap: runs that overshoot never grow memory
                }
            } catch (Throwable t) {
                log.debug("Resource sampling tick failed: {}", t.toString());
            }
        }

        public ResourceReport stop() {
            if (!available) return ResourceReport.unavailable(props.monitoring().enabled()
                    ? "OSHI could not initialise on this platform" : "Disabled by configuration");
            if (future != null) future.cancel(false);
            List<ResourceSample> copy;
            synchronized (samples) { copy = List.copyOf(samples); }
            return new ResourceReport(true, "Measures the machine running DR41/k6, not the target server (unless it is the same machine).",
                    interval, summarize(copy), copy);
        }

        private ResourceSummary summarize(List<ResourceSample> s) {
            if (s.isEmpty()) return null;
            double sumCpu = 0, peakCpu = 0, sumMem = 0, peakMem = 0, peakK6Cpu = 0, peakAppCpu = 0;
            long peakUsed = 0, peakK6Rss = 0, peakAppRss = 0;
            int saturated = 0;
            double limit = props.monitoring().saturationCpuPercent();
            for (ResourceSample x : s) {
                sumCpu += x.cpuPercent();
                sumMem += x.memoryUsedPercent();
                peakCpu = Math.max(peakCpu, x.cpuPercent());
                peakMem = Math.max(peakMem, x.memoryUsedPercent());
                peakUsed = Math.max(peakUsed, x.memoryUsedBytes());
                peakK6Cpu = Math.max(peakK6Cpu, x.k6CpuPercent());
                peakK6Rss = Math.max(peakK6Rss, x.k6RssBytes());
                peakAppCpu = Math.max(peakAppCpu, x.appCpuPercent());
                peakAppRss = Math.max(peakAppRss, x.appRssBytes());
                if (x.cpuPercent() >= limit) saturated++;
            }
            boolean sat = s.size() >= 3 && saturated >= 0.15 * s.size();
            return new ResourceSummary(r1(sumCpu / s.size()), r1(peakCpu), r1(sumMem / s.size()), r1(peakMem), peakUsed,
                    r1(peakK6Cpu), peakK6Rss, r1(peakAppCpu), peakAppRss, sat);
        }
    }

    private static double pct(double v) {
        if (Double.isNaN(v) || v < 0) return 0;
        return r1(Math.min(100.0, v));
    }

    private static double r1(double v) { return Math.round(v * 10.0) / 10.0; }
}
