package io.github.carlosphenomenal.dr41.web;

import io.github.carlosphenomenal.dr41.config.AppProperties;
import io.github.carlosphenomenal.dr41.execution.TestRunService;
import io.github.carlosphenomenal.dr41.k6.K6Runtime;
import io.github.carlosphenomenal.dr41.monitor.ResourceMonitor;
import io.github.carlosphenomenal.dr41.report.HardwareInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    public record Health(String status, K6Runtime.Status k6, HardwareInfo hardware, boolean resourceMonitoring,
                         int activeRuns, int queuedRuns, AppProperties.Limits limits) {}

    private final K6Runtime k6;
    private final ResourceMonitor monitor;
    private final TestRunService service;
    private final AppProperties props;

    public SystemController(K6Runtime k6, ResourceMonitor monitor, TestRunService service, AppProperties props) {
        this.k6 = k6;
        this.monitor = monitor;
        this.service = service;
        this.props = props;
    }

    /** Call this first: tells a UI whether tests can run and, if not, exactly how to fix it. */
    @GetMapping("/health")
    public Health health() {
        K6Runtime.Status s = k6.status();
        return new Health(s.available() ? "UP" : "DEGRADED", s, monitor.hardware(), monitor.available(),
                service.activeRuns(), service.queuedRuns(), props.limits());
    }
}
