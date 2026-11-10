package io.github.carlosphenomenal.dr41.error;

import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** A failure that maps to a specific HTTP status and a stable, machine-readable error code. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<String> details;

    public ApiException(HttpStatus status, String code, String message, List<String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public List<String> getDetails() { return details; }

    public static ApiException runNotFound(UUID id) {
        return new ApiException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "No test run exists with ID " + id);
    }

    public static ApiException invalidPlan(List<String> violations) {
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(violations));
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PLAN",
                "The test plan is invalid (" + unique.size() + " problem" + (unique.size() == 1 ? "" : "s") + ")", unique);
    }
}
