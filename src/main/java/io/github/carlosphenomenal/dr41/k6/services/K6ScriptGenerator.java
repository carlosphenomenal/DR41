package io.github.carlosphenomenal.dr41.k6.services;

import io.github.carlosphenomenal.dr41.testexecution.dtos.StartTestRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class K6ScriptGenerator {
    private final ObjectMapper objectMapper;

    public K6ScriptGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String generate(StartTestRequest request) {
        String url = objectMapper.writeValueAsString(request.url());
        String method = objectMapper.writeValueAsString(request.method().toUpperCase());
        String headers = objectMapper.writeValueAsString(request.headers());
        String body = request.body() == null || request.body().isBlank()
                ? "null" : objectMapper.writeValueAsString(request.body());
        int expectedStatus = request.expectedStatus();

        return """
                import http from 'k6/http';
                import { check } from 'k6';

                export const options = {
                  vus: %d,
                  duration: %s,
                  discardResponseBodies: true,
                };

                const targetUrl = %s;
                const requestMethod = %s;
                const requestHeaders = %s;
                const requestBody = %s;

                export default function () {
                  const response = http.request(
                    requestMethod,
                    targetUrl,
                    requestBody,
                    { headers: requestHeaders, tags: { endpoint: targetUrl, method: requestMethod } }
                  );

                  check(response, {
                    'status matches expected status': (r) => r.status === %d,
                  });
                }
                """.formatted(request.virtualUsers(), quote(request.duration()), url, method, headers, body, expectedStatus);
    }

    private String quote(String value) {
        return objectMapper.writeValueAsString(value);
    }
}
