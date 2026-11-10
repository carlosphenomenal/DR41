package io.github.carlosphenomenal.dr41.report;

import java.util.List;

public record ErrorInfo(String code, String message, List<String> details) {
    public ErrorInfo {
        details = details == null ? List.of() : List.copyOf(details);
    }
}
