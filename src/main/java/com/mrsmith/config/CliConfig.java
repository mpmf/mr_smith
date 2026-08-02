package com.mrsmith.config;

import java.nio.file.Path;

public record CliConfig(String apiKey, String baseUrl, String model, String systemPrompt,
                        Integer maxContextTokens, Boolean includeUsage, Path sessionsDir) {

    public CliConfig(String apiKey, String baseUrl, String model, String systemPrompt,
                     Integer maxContextTokens, Boolean includeUsage) {
        this(apiKey, baseUrl, model, systemPrompt, maxContextTokens, includeUsage, null);
    }

    public static CliConfig empty() {
        return new CliConfig(null, null, null, null, null, null, null);
    }
}
