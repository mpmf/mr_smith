package com.mrsmith.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentRuntimeTest {

    @Test
    void exposesAgentProviderAndGlobals() {
        AgentConfig agent = new AgentConfig("coder", "p", "model-x", "sys", 8192, 5, 200, List.of("shell"));
        ProviderConfig provider = new ProviderConfig("p", "sk", "https://example.com/v1");
        AgentRuntime runtime = new AgentRuntime(agent, provider, new AgentRuntime.Globals(false));
        assertEquals(agent, runtime.agent());
        assertEquals(provider, runtime.provider());
        assertFalse(runtime.globals().includeUsage());
    }
}
