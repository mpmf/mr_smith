package com.mrsmith.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record AppConfig(String apiKey, String baseUrl, String model, String systemPrompt,
                        Integer maxContextTokens, boolean includeUsage, Path sessionsDir,
                        List<String> tools, Integer maxToolRounds, Integer maxToolCallsPerSession) {

    public AppConfig {
        Objects.requireNonNull(apiKey, "apiKey is required");
        Objects.requireNonNull(baseUrl, "baseUrl is required");
        Objects.requireNonNull(model, "model is required");
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public AppConfig(String apiKey, String baseUrl, String model, String systemPrompt) {
        this(apiKey, baseUrl, model, systemPrompt, null, true, null, List.of(), null, null);
    }

    public AppConfig(String apiKey, String baseUrl, String model, String systemPrompt,
                     Integer maxContextTokens, boolean includeUsage) {
        this(apiKey, baseUrl, model, systemPrompt, maxContextTokens, includeUsage, null, List.of(), null, null);
    }

    public AppConfig(String apiKey, String baseUrl, String model, String systemPrompt,
                     Integer maxContextTokens, boolean includeUsage, Path sessionsDir) {
        this(apiKey, baseUrl, model, systemPrompt, maxContextTokens, includeUsage, sessionsDir, List.of(), null, null);
    }
}
