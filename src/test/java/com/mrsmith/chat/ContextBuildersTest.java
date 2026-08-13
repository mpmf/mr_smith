package com.mrsmith.chat;

import com.mrsmith.config.AgentConfig;
import com.mrsmith.config.AgentRuntime;
import com.mrsmith.config.ContextStrategy;
import com.mrsmith.config.ProviderConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextBuildersTest {

    private static AgentRuntime runtime(String strategy, Integer maxContext, double ratio) {
        AgentConfig agent = new AgentConfig("a", "p", "m", null, maxContext, null, null,
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                ContextStrategy.parse(strategy));
        ProviderConfig provider = new ProviderConfig("p", "sk", "https://example.com/v1");
        return new AgentRuntime(agent, provider, new AgentRuntime.Globals(true, ratio));
    }

    @Test
    void createReturnsFullForFullStrategy() {
        assertTrue(ContextBuilders.create(runtime("full", 128000, 0.75)) instanceof FullContextBuilder);
    }

    @Test
    void createReturnsSlidingForSlidingStrategy() {
        assertTrue(ContextBuilders.create(runtime("sliding", 128000, 0.75)) instanceof SlidingWindowContextBuilder);
    }

    @Test
    void windowBudgetUsesRatioOfMaxContext() {
        assertEquals(96000, ContextBuilders.windowBudget(runtime("sliding", 128000, 0.75)));
    }

    @Test
    void windowBudgetRoundsToNearestToken() {
        assertEquals(5, ContextBuilders.windowBudget(runtime("sliding", 6, 0.75)));
    }

    @Test
    void windowBudgetFallsBackToDefaultBudgetWhenUnset() {
        assertEquals(75000, ContextBuilders.windowBudget(runtime("sliding", null, 0.75)));
        assertEquals(75000, ContextBuilders.windowBudget(runtime("sliding", 0, 0.75)));
    }
}
