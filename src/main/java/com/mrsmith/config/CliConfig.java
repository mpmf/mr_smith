package com.mrsmith.config;

public record CliConfig(String apiKey, String baseUrl, String model, String systemPrompt,
                        Integer maxContextTokens, Boolean includeUsage) {

    public static CliConfig empty() {
        return new CliConfig(null, null, null, null, null, null);
    }
}
