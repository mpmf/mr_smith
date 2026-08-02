package com.mrsmith.config;

public record AgentConfig(String name, String provider, String model,
                          String systemPrompt, Integer maxContextTokens) {
}
