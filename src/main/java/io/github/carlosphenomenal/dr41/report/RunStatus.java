package io.github.carlosphenomenal.dr41.report;

public enum RunStatus {
    QUEUED, PREPARING, RUNNING, COMPLETED, FAILED, CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
