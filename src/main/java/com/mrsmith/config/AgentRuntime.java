package com.mrsmith.config;

public record AgentRuntime(AgentConfig agent, ProviderConfig provider, AgentRuntime.Globals globals) {

    public static final double DEFAULT_CONTEXT_WINDOW_RATIO = 0.75;

    public record Globals(boolean includeUsage, double contextWindowRatio) {

        public Globals(boolean includeUsage) {
            this(includeUsage, DEFAULT_CONTEXT_WINDOW_RATIO);
        }
    }
}
