package com.mrsmith.provider;

import com.mrsmith.config.AgentRuntime;

public interface ProviderFactory {

    Provider create(AgentRuntime runtime);
}
