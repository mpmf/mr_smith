package com.mrsmith.config;

public record CliConfig(String model, String baseUrl, String systemPrompt, String apiKey,
                        Integer maxContextTokens, Boolean includeUsage) {

    public static CliConfig empty() {
        return new CliConfig(null, null, null, null, null, null);
    }
}
