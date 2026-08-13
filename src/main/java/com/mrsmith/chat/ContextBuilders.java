package com.mrsmith.chat;

import com.mrsmith.config.AgentRuntime;
import com.mrsmith.config.ContextStrategy;

public final class ContextBuilders {

    private ContextBuilders() {
    }

    public static ContextBuilder create(AgentRuntime runtime) {
        ContextStrategy strategy = runtime.agent().contextBuilder();
        if (strategy == ContextStrategy.SLIDING) {
            return new SlidingWindowContextBuilder();
        }
        return new FullContextBuilder();
    }

    public static int windowBudget(AgentRuntime runtime) {
        Integer maxContext = runtime.agent().maxContextTokens();
        int base = (maxContext != null && maxContext > 0)
                ? maxContext
                : SlidingWindowContextBuilder.DEFAULT_BUDGET;
        return (int) Math.round(base * runtime.globals().contextWindowRatio());
    }
}
