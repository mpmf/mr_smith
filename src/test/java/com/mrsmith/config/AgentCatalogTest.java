package com.mrsmith.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCatalogTest {

    private final ProviderConfig provider = new ProviderConfig("opencode", "sk", "https://example.com/v1");
    private final AgentConfig agent = new AgentConfig("coder", "opencode", "model-x", "be helpful", 128000);

    @Test
    void resolveMergesProviderAgentAndGlobals() {
        AgentCatalog catalog = new AgentCatalog(List.of(provider), List.of(agent), "coder", false, Path.of("/tmp/s"));
        AppConfig config = catalog.resolve("coder");
        assertEquals("sk", config.apiKey());
        assertEquals("https://example.com/v1", config.baseUrl());
        assertEquals("model-x", config.model());
        assertEquals("be helpful", config.systemPrompt());
        assertEquals(128000, config.maxContextTokens());
        assertEquals(false, config.includeUsage());
        assertEquals(Path.of("/tmp/s"), config.sessionsDir());
    }

    @Test
    void resolveThrowsOnUnknownAgent() {
        AgentCatalog catalog = new AgentCatalog(List.of(provider), List.of(agent), "coder", true, Path.of("/tmp/s"));
        assertThrows(ConfigException.class, () -> catalog.resolve("nope"));
    }

    @Test
    void defaultNameReturnsDefault() {
        AgentCatalog catalog = new AgentCatalog(List.of(provider), List.of(agent), "coder", true, Path.of("/tmp/s"));
        assertEquals("coder", catalog.defaultName());
    }

    @Test
    void duplicateAgentNameThrows() {
        assertThrows(ConfigException.class, () -> new AgentCatalog(
                List.of(provider), List.of(agent, agent), "coder", true, Path.of("/tmp/s")));
    }

    @Test
    void unknownProviderReferenceThrows() {
        assertThrows(ConfigException.class, () -> new AgentCatalog(
                List.of(provider), List.of(new AgentConfig("x", "missing", "m", null, null)), "x", true, Path.of("/tmp/s")));
    }

    @Test
    void missingDefaultAgentThrows() {
        assertThrows(ConfigException.class, () -> new AgentCatalog(
                List.of(provider), List.of(agent), "nope", true, Path.of("/tmp/s")));
    }

    @Test
    void noAgentsThrows() {
        assertThrows(ConfigException.class, () -> new AgentCatalog(
                List.of(provider), List.of(), "coder", true, Path.of("/tmp/s")));
    }

    @Test
    void providerMissingApiKeyThrows() {
        assertThrows(ConfigException.class, () -> new AgentCatalog(
                List.of(new ProviderConfig("opencode", null, "https://example.com/v1")),
                List.of(agent), "coder", true, Path.of("/tmp/s")));
    }

    @Test
    void providerBlankApiKeyThrows() {
        assertThrows(ConfigException.class, () -> new AgentCatalog(
                List.of(new ProviderConfig("opencode", "", "https://example.com/v1")),
                List.of(agent), "coder", true, Path.of("/tmp/s")));
    }

    @Test
    void agentMissingModelThrows() {
        assertThrows(ConfigException.class, () -> new AgentCatalog(
                List.of(provider), List.of(new AgentConfig("x", "opencode", null, null, null)),
                "x", true, Path.of("/tmp/s")));
    }

    @Test
    void agentNamesReturnsAll() {
        AgentCatalog catalog = new AgentCatalog(List.of(provider), List.of(agent), "coder", true, Path.of("/tmp/s"));
        assertTrue(catalog.agentNames().contains("coder"));
    }
}
