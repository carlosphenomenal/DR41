package io.github.carlosphenomenal.dr41.web;

import io.github.carlosphenomenal.dr41.config.AppProperties;
import io.github.carlosphenomenal.dr41.error.ApiException;
import io.github.carlosphenomenal.dr41.execution.TestRun;
import io.github.carlosphenomenal.dr41.execution.TestRunService;
import io.github.carlosphenomenal.dr41.plan.PlanParser;
import io.github.carlosphenomenal.dr41.plan.PlanResolver;
import io.github.carlosphenomenal.dr41.report.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/tests")
public class TestController {
    private static final String[] PLAN_TYPES = {"application/json", "application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml"};

    private final TestRunService service;
    private final PlanParser parser;
    private final AppProperties props;

    public TestController(TestRunService service, PlanParser parser, AppProperties props) {
        this.service = service;
        this.parser = parser;
        this.props = props;
    }

    /** Start a test from a JSON or YAML plan sent as the request body. */
    @PostMapping(consumes = {"application/json", "application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml"})
    public ResponseEntity<RunView> start(@RequestBody byte[] body,
                                         @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType) {
        return accepted(service.submit(parser.parse(limit(body), isJson(contentType))));
    }

    /** Start a test from an uploaded plan file (.yaml/.yml/.json). */
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RunView> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw ApiException.invalidPlan(List.of("The uploaded file is empty"));
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        boolean json = name.endsWith(".json") || "application/json".equalsIgnoreCase(file.getContentType());
        return accepted(service.submit(parser.parse(limit(file.getBytes()), json)));
    }

    /** Dry run: validates the plan, shows what would execute, and checks k6 + memory. Starts nothing. */
    @PostMapping(path = "/validate", consumes = {"application/json", "application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml"})
    public TestRunService.Validation validate(@RequestBody byte[] body,
                                              @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType) {
        return service.validate(parser.parse(limit(body), isJson(contentType)));
    }

    @GetMapping
    public List<RunView> list() { return service.list(); }

    @GetMapping("/{id}")
    public RunView get(@PathVariable UUID id) { return service.view(service.get(id)); }

    @GetMapping("/{id}/report")
    public TestReport report(@PathVariable UUID id) { return service.report(id); }

    @GetMapping(value = "/{id}/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public String logs(@PathVariable UUID id) { return service.logs(id); }

    @DeleteMapping("/{id}")
    public ResponseEntity<RunView> cancel(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(service.view(service.cancel(id)));
    }

    // ------------------------------------------------------------------------------ graph-friendly CSV

    @GetMapping(value = "/{id}/timeseries.csv", produces = "text/csv")
    public ResponseEntity<String> timeseriesCsv(@PathVariable UUID id) {
        TestReport r = service.report(id);
        StringBuilder sb = new StringBuilder("t,time,series,requests,errors,rps,avg_ms,p50_ms,p95_ms,p99_ms,max_ms\n");
        r.timeline().series().forEach((series, points) -> {
            String label = PlanResolver.ALL_SERIES.equals(series) ? "ALL" : series;
            for (TimelinePoint p : points) {
                sb.append(p.t()).append(',').append(Instant.ofEpochSecond(p.t())).append(',').append(label).append(',')
                        .append(p.requests()).append(',').append(p.errors()).append(',').append(p.rps()).append(',')
                        .append(p.avgMs()).append(',').append(p.p50Ms()).append(',').append(p.p95Ms()).append(',')
                        .append(p.p99Ms()).append(',').append(p.maxMs()).append('\n');
            }
        });
        return csv(id, "timeseries", sb);
    }

    @GetMapping(value = "/{id}/resources.csv", produces = "text/csv")
    public ResponseEntity<String> resourcesCsv(@PathVariable UUID id) {
        TestReport r = service.report(id);
        StringBuilder sb = new StringBuilder("t,time,cpu_percent,memory_used_bytes,memory_used_percent,k6_cpu_percent,k6_rss_bytes,app_cpu_percent,app_rss_bytes\n");
        for (ResourceSample s : r.resources().samples()) {
            sb.append(s.t()).append(',').append(Instant.ofEpochSecond(s.t())).append(',').append(s.cpuPercent()).append(',')
                    .append(s.memoryUsedBytes()).append(',').append(s.memoryUsedPercent()).append(',').append(s.k6CpuPercent()).append(',')
                    .append(s.k6RssBytes()).append(',').append(s.appCpuPercent()).append(',').append(s.appRssBytes()).append('\n');
        }
        return csv(id, "resources", sb);
    }

    // --------------------------------------------------------------------------------------- helpers

    private ResponseEntity<RunView> accepted(TestRun run) {
        return ResponseEntity.accepted().location(URI.create("/api/tests/" + run.id())).body(service.view(run));
    }

    private static ResponseEntity<String> csv(UUID id, String kind, StringBuilder body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"dr41-" + id + "-" + kind + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(body.toString());
    }

    private byte[] limit(byte[] body) {
        if (body != null && body.length > props.limits().maxPlanBytes()) {
            throw new ApiException(
                    HttpStatus.valueOf(413),
                    "PAYLOAD_TOO_LARGE",
                    "The plan is larger than " + props.limits().maxPlanBytes() + " bytes."
            );
        }
        return body;
    }

    private static boolean isJson(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json");
    }
}
