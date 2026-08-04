package com.mrsmith.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void missingConfigFileThrows() {
        assertThrows(ConfigException.class,
                () -> ConfigLoader.load(noFile(), CliConfig.empty(), Map.of()));
    }

    @Test
    void malformedConfigFileThrows() throws IOException {
        Path file = writeConfig("not valid json {{{");
        assertThrows(ConfigException.class,
                () -> ConfigLoader.load(file, CliConfig.empty(), Map.of()));
    }

    @Test
    void oldFormatWithoutAgentsThrows() throws IOException {
        Path file = writeConfig("{ \"model\": \"gpt-4o-mini\", \"baseUrl\": \"https://api.openai.com/v1\" }");
        assertThrows(ConfigException.class,
                () -> ConfigLoader.load(file, CliConfig.empty(), Map.of()));
    }

    @Test
    void loadsProvidersAgentsAndGlobals() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [
                    { "name": "opencode", "apiKey": "sk-x", "baseUrl": "https://opencode.ai/zen/go/v1" }
                  ],
                  "agents": [
                    { "name": "coder", "provider": "opencode", "model": "model-x", "systemPrompt": "be helpful", "maxContextTokens": 128000 }
                  ],
                  "defaultAgent": "coder",
                  "includeUsage": false,
                  "sessionsDir": "/tmp/my-sessions"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals("coder", catalog.defaultName());
        assertEquals(Path.of("/tmp/my-sessions"), catalog.sessionsDir());
        AppConfig config = catalog.resolve("coder");
        assertEquals("sk-x", config.apiKey());
        assertEquals("https://opencode.ai/zen/go/v1", config.baseUrl());
        assertEquals("model-x", config.model());
        assertEquals("be helpful", config.systemPrompt());
        assertEquals(128000, config.maxContextTokens());
        assertFalse(config.includeUsage());
    }

    @Test
    void defaultsIncludeUsageTrueAndSessionsDirConfigHome() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertTrue(catalog.resolve("a").includeUsage());
        assertEquals(Path.of(System.getProperty("user.home"), ".config", "mrsmith", "sessions"),
                catalog.sessionsDir());
    }

    @Test
    void sessionsDirFromEnv() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(),
                Map.of("MRSMITH_SESSIONS_DIR", "/tmp/env-sessions"));
        assertEquals(Path.of("/tmp/env-sessions"), catalog.sessionsDir());
    }

    @Test
    void sessionsDirFromCliOverrides() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a",
                  "sessionsDir": "/tmp/file-sessions"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file,
                new CliConfig(null, Path.of("/tmp/cli-sessions")), Map.of());
        assertEquals(Path.of("/tmp/cli-sessions"), catalog.sessionsDir());
    }

    @Test
    void parsesPerAgentTools() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m", "tools": ["shell", "read_file"] } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(List.of("shell", "read_file"), catalog.resolve("a").tools());
    }

    @Test
    void toolsDefaultToEmpty() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(List.of(), catalog.resolve("a").tools());
    }

    private Path noFile() {
        return tempDir.resolve("missing.json");
    }

    private Path writeConfig(String content) throws IOException {
        Path file = tempDir.resolve("config.json");
        Files.writeString(file, content);
        return file;
    }
}
