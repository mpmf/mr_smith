package com.mrsmith.config;

import java.util.List;

public record AgentConfig(String name, String provider, String model,
                          String systemPrompt, Integer maxContextTokens,
                          Integer maxToolRounds, Integer maxToolCallsPerSession,
                          List<String> tools,
                          List<String> shellHarmlessCommands,
                          List<String> shellDangerousCommands,
                          ContextStrategy contextBuilder) {

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens) {
        this(name, provider, model, systemPrompt, maxContextTokens, null, null,
                List.of(), List.of(), List.of(), ContextStrategy.FULL);
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, List<String> tools) {
        this(name, provider, model, systemPrompt, maxContextTokens, null, null,
                tools, List.of(), List.of(), ContextStrategy.FULL);
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, Integer maxToolRounds) {
        this(name, provider, model, systemPrompt, maxContextTokens, maxToolRounds, null,
                List.of(), List.of(), List.of(), ContextStrategy.FULL);
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, Integer maxToolRounds,
                       Integer maxToolCallsPerSession) {
        this(name, provider, model, systemPrompt, maxContextTokens, maxToolRounds,
                maxToolCallsPerSession, List.of(), List.of(), List.of(), ContextStrategy.FULL);
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, Integer maxToolRounds,
                       Integer maxToolCallsPerSession, List<String> tools) {
        this(name, provider, model, systemPrompt, maxContextTokens, maxToolRounds,
                maxToolCallsPerSession, tools, List.of(), List.of(), ContextStrategy.FULL);
    }
}
