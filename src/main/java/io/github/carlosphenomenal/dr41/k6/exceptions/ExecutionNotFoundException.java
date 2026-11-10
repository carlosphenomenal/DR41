package io.github.carlosphenomenal.dr41.k6.exceptions;

import java.util.UUID;

public class ExecutionNotFoundException extends RuntimeException {
    public ExecutionNotFoundException(UUID id) { super("No test execution exists with ID " + id); }
}
