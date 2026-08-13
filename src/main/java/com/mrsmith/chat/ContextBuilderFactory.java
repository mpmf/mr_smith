package com.mrsmith.chat;

import com.mrsmith.config.AgentRuntime;

@FunctionalInterface
public interface ContextBuilderFactory {

    ContextBuilder create(AgentRuntime runtime);

    static ContextBuilderFactory full() {
        return runtime -> new FullContextBuilder();
    }
}
