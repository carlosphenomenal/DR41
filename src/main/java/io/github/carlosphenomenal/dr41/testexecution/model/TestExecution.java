package io.github.carlosphenomenal.dr41.testexecution.model;

import io.github.carlosphenomenal.dr41.testexecution.dtos.StartTestRequest;
import io.github.carlosphenomenal.dr41.testexecution.enums.TestStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Getter
public class TestExecution {
    private final UUID id;
    private final StartTestRequest request;
    private final Instant createdAt;
    private final AtomicReference<TestStatus> status = new AtomicReference<>(TestStatus.QUEUED);
    @Setter
    private volatile Instant startedAt;
    @Setter
    private volatile Instant completedAt;
    @Setter
    private volatile Integer exitCode;
    @Setter
    private volatile String error;
    private volatile String logs = "";
    @Setter
    private volatile Object summary;
    @Setter
    private volatile Process process;

    public TestExecution(UUID id, StartTestRequest request) {
        this.id = id;
        this.request = request;
        this.createdAt = Instant.now();
    }

    public TestStatus getStatus() { return status.get(); }
    public void setStatus(TestStatus value) { status.set(value); }

    public synchronized void appendLog(String line) {
        // Keep memory bounded; this is a diagnostic tail, not a permanent log store.
        String next = logs + line + "\n";
        logs = next.length() > 50_000 ? next.substring(next.length() - 50_000) : next;
    }

}
