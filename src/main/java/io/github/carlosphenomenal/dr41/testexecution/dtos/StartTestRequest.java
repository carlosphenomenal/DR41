package io.github.carlosphenomenal.dr41.testexecution.dtos;

import jakarta.validation.constraints.*;

import java.util.Map;

public record StartTestRequest(
        @NotBlank @Size(max = 2048) @Pattern(regexp = "https?://.+", flags = Pattern.Flag.CASE_INSENSITIVE)
        String url,
        @NotBlank @Pattern(regexp = "GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS", flags = Pattern.Flag.CASE_INSENSITIVE)
        String method,
        Map<@Size(max = 256) String, @Size(max = 8192) String> headers,
        @Size(max = 1_000_000)
        String body,
        @NotNull @Min(1)
        Integer virtualUsers,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,3}([smh])")
        String duration,
        @Min(100) @Max(599)
        Integer expectedStatus
) {
    public StartTestRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        expectedStatus = expectedStatus == null ? 200 : expectedStatus;
    }
}
