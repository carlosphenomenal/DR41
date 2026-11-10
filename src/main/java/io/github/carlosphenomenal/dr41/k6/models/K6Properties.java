package io.github.carlosphenomenal.dr41.k6.models;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "app.k6")
public record K6Properties(String executable, Path workDirectory) {}
