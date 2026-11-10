package io.github.carlosphenomenal.dr41.testexecution.controllers;

import io.github.carlosphenomenal.dr41.testexecution.dtos.StartTestRequest;
import io.github.carlosphenomenal.dr41.testexecution.model.TestExecution;
import io.github.carlosphenomenal.dr41.testexecution.services.TestExecutionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/tests")
public class TestExecutionController {
    private final TestExecutionService service;

    public TestExecutionController(TestExecutionService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<Map<String, Object>> start(@Valid @RequestBody StartTestRequest request) {
        TestExecution execution = service.start(request);
        return ResponseEntity.accepted()
                .location(URI.create("/api/tests/" + execution.getId()))
                .body(Map.of("id", execution.getId(), "status", execution.getStatus(),
                        "statusUrl", "/api/tests/" + execution.getId()));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        TestExecution e = service.get(id);
        return Map.of("id", e.getId(), "status", e.getStatus(), "createdAt", e.getCreatedAt(),
                "startedAt", e.getStartedAt() == null ? "" : e.getStartedAt(),
                "completedAt", e.getCompletedAt() == null ? "" : e.getCompletedAt(),
                "exitCode", e.getExitCode() == null ? "" : e.getExitCode(),
                "error", e.getError() == null ? "" : e.getError(),
                "summary", e.getSummary() == null ? Map.of() : e.getSummary());
    }

    @GetMapping("/{id}/logs")
    public Map<String, String> logs(@PathVariable UUID id) {
        return Map.of("logs", service.get(id).getLogs());
    }

    @GetMapping
    public Map<String, Object> list() { return service.list(); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id) { service.cancel(id); }
}
