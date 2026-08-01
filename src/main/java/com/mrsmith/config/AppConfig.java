package com.mrsmith.config;

import java.util.Objects;

public record AppConfig(String apiKey, String baseUrl, String model, String systemPrompt,
                        Integer maxContextTokens, boolean includeUsage) {

    public AppConfig {
        Objects.requireNonNull(apiKey, "apiKey is required");
        Objects.requireNonNull(baseUrl, "baseUrl is required");
        Objects.requireNonNull(model, "model is required");
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

    public AppConfig(String apiKey, String baseUrl, String model, String systemPrompt) {
        this(apiKey, baseUrl, model, systemPrompt, null, true);
    }
}
