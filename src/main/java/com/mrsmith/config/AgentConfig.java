package com.mrsmith.config;

import java.util.List;

public record AgentConfig(String name, String provider, String model,
                          String systemPrompt, Integer maxContextTokens,
                          Integer maxToolRounds, Integer maxToolCallsPerSession,
                          List<String> tools) {

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens) {
        this(name, provider, model, systemPrompt, maxContextTokens, null, null, List.of());
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, List<String> tools) {
        this(name, provider, model, systemPrompt, maxContextTokens, null, null, tools);
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, Integer maxToolRounds) {
        this(name, provider, model, systemPrompt, maxContextTokens, maxToolRounds, null, List.of());
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, Integer maxToolRounds,
                       Integer maxToolCallsPerSession) {
        this(name, provider, model, systemPrompt, maxContextTokens, maxToolRounds, maxToolCallsPerSession, List.of());
    }
}
