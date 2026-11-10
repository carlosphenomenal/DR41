package io.github.carlosphenomenal.dr41.testexecution.services;

import io.github.carlosphenomenal.dr41.k6.models.K6Properties;
import io.github.carlosphenomenal.dr41.k6.exceptions.ExecutionNotFoundException;
import io.github.carlosphenomenal.dr41.k6.services.K6ScriptGenerator;
import io.github.carlosphenomenal.dr41.testexecution.dtos.StartTestRequest;
import io.github.carlosphenomenal.dr41.testexecution.model.TestExecution;
import io.github.carlosphenomenal.dr41.testexecution.enums.TestStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

@Service
public class TestExecutionService {
    private final Map<UUID, TestExecution> executions = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final K6Properties properties;
    private final K6ScriptGenerator scriptGenerator;
    private final ObjectMapper objectMapper;

    public TestExecutionService(
            @Qualifier("k6Executor") ExecutorService testExecutor,
            K6Properties properties,
            K6ScriptGenerator scriptGenerator,
            ObjectMapper objectMapper) {
        this.executor = testExecutor;
        this.properties = properties;
        this.scriptGenerator = scriptGenerator;
        this.objectMapper = objectMapper;
    }

    public TestExecution start(StartTestRequest request) {
        UUID id = UUID.randomUUID();
        TestExecution execution = new TestExecution(id, request);
        executions.put(id, execution);
        executor.submit(() -> run(execution));
        return execution;
    }

    public TestExecution get(UUID id) {
        TestExecution execution = executions.get(id);
        if (execution == null) throw new ExecutionNotFoundException(id);
        return execution;
    }

    public void cancel(UUID id) {
        TestExecution execution = get(id);
        TestStatus status = execution.getStatus();
        if (status == TestStatus.COMPLETED || status == TestStatus.FAILED || status == TestStatus.CANCELLED) return;
        Process process = execution.getProcess();
        if (process != null && process.isAlive()) {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            try {
                if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        execution.setStatus(TestStatus.CANCELLED);
        execution.setCompletedAt(Instant.now());
    }

    private void run(TestExecution execution) {
        if (execution.getStatus() == TestStatus.CANCELLED) return;
        Path runDirectory = properties.workDirectory().resolve(execution.getId().toString());
        try {
            Files.createDirectories(runDirectory);
            Path script = runDirectory.resolve("test.js");
            Path summary = runDirectory.resolve("summary.json");
            Files.writeString(script, scriptGenerator.generate(execution.getRequest()), StandardCharsets.UTF_8);

            ProcessBuilder builder = new ProcessBuilder(
                    properties.executable(), "run", "--summary-export=" + summary.toAbsolutePath(), script.toAbsolutePath().toString());
            builder.directory(runDirectory.toFile());
            builder.redirectErrorStream(true);
            builder.environment().put("NO_COLOR", "1");

            Process process = builder.start();
            execution.setProcess(process);
            execution.setStartedAt(Instant.now());
            execution.setStatus(TestStatus.RUNNING);

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) execution.appendLog(line);
            }

            int exitCode = process.waitFor();
            execution.setExitCode(exitCode);
            if (execution.getStatus() == TestStatus.CANCELLED) return;

            if (Files.exists(summary)) {
                execution.setSummary(objectMapper.readValue(summary.toFile(), Object.class));
            }
            if (exitCode == 0) {
                execution.setStatus(TestStatus.COMPLETED);
            } else {
                execution.setStatus(TestStatus.FAILED);
                execution.setError("k6 exited with code " + exitCode + ". Inspect logs for details.");
            }
        } catch (IOException e) {
            execution.setStatus(TestStatus.FAILED);
            execution.setError("Could not start or read k6. Is k6 installed and available on PATH? Details: " + e.getMessage());
            execution.appendLog(execution.getError());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (execution.getStatus() != TestStatus.CANCELLED) {
                execution.setStatus(TestStatus.FAILED);
                execution.setError("Test worker was interrupted.");
            }
        } catch (Exception e) {
            execution.setStatus(TestStatus.FAILED);
            execution.setError("Unexpected test execution error: " + e.getMessage());
            execution.appendLog(execution.getError());
        } finally {
            execution.setCompletedAt(Instant.now());
            execution.setProcess(null);
        }
    }

    public Map<String, Object> list() {
        return Map.of("executions", executions.values().stream()
                .sorted(Comparator.comparing(TestExecution::getCreatedAt).reversed())
                .map(this::toSummary).toList());
    }

    private Map<String, Object> toSummary(TestExecution e) {
        return Map.of("id", e.getId(), "status", e.getStatus(), "createdAt", e.getCreatedAt(),
                "startedAt", e.getStartedAt() == null ? "" : e.getStartedAt(),
                "completedAt", e.getCompletedAt() == null ? "" : e.getCompletedAt());
    }
}
