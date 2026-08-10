package com.mrsmith.config;

public record ProviderConfig(String name, String apiKey, String baseUrl) {

    public ProviderConfig {
        if (baseUrl != null && baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }
}
