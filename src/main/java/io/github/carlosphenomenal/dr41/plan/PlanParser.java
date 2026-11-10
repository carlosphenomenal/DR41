package io.github.carlosphenomenal.dr41.plan;

import io.github.carlosphenomenal.dr41.error.ApiException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Strict parsing: unknown fields are errors (a typo like "virtualUser" must not silently become "no VUs"),
 * and trailing garbage is rejected. SnakeYAML's built-in alias/size limits protect against YAML bombs.
 */
@Component
public class PlanParser {
    private final ObjectMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final ObjectMapper yaml = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public TestPlanRequest parse(byte[] raw, boolean isJson) {
        if (raw == null || raw.length == 0) {
            throw ApiException.invalidPlan(List.of("The request body is empty"));
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1); // strip BOM
        if (text.isBlank()) throw ApiException.invalidPlan(List.of("The request body is empty"));
        try {
            TestPlanRequest plan = (isJson ? json : yaml).readValue(text, TestPlanRequest.class);
            if (plan == null) throw ApiException.invalidPlan(List.of("The document contains no test plan"));
            return plan;
        } catch (JacksonException e) {
            String where = e.getLocation() == null ? ""
                    : " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")";
            String msg = String.valueOf(e.getOriginalMessage());
            if (msg.length() > 300) msg = msg.substring(0, 300) + "…";
            throw ApiException.invalidPlan(List.of((isJson ? "JSON" : "YAML") + " could not be read" + where + ": " + msg));
        }
    }
}
