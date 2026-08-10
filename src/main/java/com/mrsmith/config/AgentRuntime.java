package com.mrsmith.config;

public record AgentRuntime(AgentConfig agent, ProviderConfig provider, AgentRuntime.Globals globals) {

    public record Globals(boolean includeUsage) {
    }
}
