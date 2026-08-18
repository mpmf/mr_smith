package com.mrsmith.config;

public record AgentRuntime(AgentConfig agent, ProviderConfig provider, AgentRuntime.Globals globals,
                           ReasoningEffort reasoning) {

    public static final double DEFAULT_CONTEXT_WINDOW_RATIO = 0.75;

    public AgentRuntime(AgentConfig agent, ProviderConfig provider, AgentRuntime.Globals globals) {
        this(agent, provider, globals, new ReasoningEffort());
    }

    public String effectiveReasoningEffort() {
        return reasoning.effective(agent.reasoningEffort());
    }

    public record Globals(boolean includeUsage, double contextWindowRatio) {

        public Globals(boolean includeUsage) {
            this(includeUsage, DEFAULT_CONTEXT_WINDOW_RATIO);
        }
    }
}
