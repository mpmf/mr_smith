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
        AgentRuntime runtime = catalog.resolve("coder");
        assertEquals("sk-x", runtime.provider().apiKey());
        assertEquals("https://opencode.ai/zen/go/v1", runtime.provider().baseUrl());
        assertEquals("model-x", runtime.agent().model());
        assertEquals("be helpful", runtime.agent().systemPrompt());
        assertEquals(128000, runtime.agent().maxContextTokens());
        assertFalse(runtime.globals().includeUsage());
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
        assertTrue(catalog.resolve("a").globals().includeUsage());
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
        assertEquals(List.of("shell", "read_file"), catalog.resolve("a").agent().tools());
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
        assertEquals(List.of(), catalog.resolve("a").agent().tools());
    }

    @Test
    void unknownToolNameInConfigFileThrows() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m", "tools": ["nope"] } ],
                  "defaultAgent": "a"
                }
                """);
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(file, CliConfig.empty(), Map.of()));
        assertTrue(e.getMessage().contains("unknown tool 'nope'"));
    }

    @Test
    void resolvesConfiguredSkillDirsAgainstAnchors() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a",
                  "projectSkillsDir": "custom/skills",
                  "globalSkillsDir": "mr-skills"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(Path.of(System.getProperty("user.dir"), "custom", "skills"), catalog.projectSkillsDir());
        assertEquals(Path.of(System.getProperty("user.home"), "mr-skills"), catalog.globalSkillsDir());
    }

    @Test
    void absoluteSkillDirsUsedAsIs() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a",
                  "projectSkillsDir": "/abs/skills",
                  "globalSkillsDir": "/abs/global"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(Path.of("/abs/skills"), catalog.projectSkillsDir());
        assertEquals(Path.of("/abs/global"), catalog.globalSkillsDir());
    }

    @Test
    void skillDirsDefaultToCurrentValues() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(Path.of(System.getProperty("user.dir"), "skills"), catalog.projectSkillsDir());
        assertEquals(Path.of(System.getProperty("user.home"), ".config", "mrsmith", "skills"),
                catalog.globalSkillsDir());
    }

    @Test
    void parsesMaxToolRoundsPerAgent() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m", "maxToolRounds": 12 } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(12, catalog.resolve("a").agent().maxToolRounds());
    }

    @Test
    void maxToolRoundsDefaultsToNull() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(null, catalog.resolve("a").agent().maxToolRounds());
    }

    @Test
    void parsesMaxToolCallsPerSessionPerAgent() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m", "maxToolCallsPerSession": 200 } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(200, catalog.resolve("a").agent().maxToolCallsPerSession());
    }

    @Test
    void maxToolCallsPerSessionDefaultsToNull() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(null, catalog.resolve("a").agent().maxToolCallsPerSession());
    }

    @Test
    void baseUrlTrailingSlashIsStripped() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1/" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals("https://example.com/v1", catalog.resolve("a").provider().baseUrl());
    }

    @Test
    void providerApiKeyFromEnvOverridesFile() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "opencode", "apiKey": "sk-file", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "opencode", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(),
                Map.of("MRSMITH_OPENCODE_API_KEY", "sk-env"));
        assertEquals("sk-env", catalog.resolve("a").provider().apiKey());
    }

    @Test
    void providerApiKeyFromEnvFillsMissingKey() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "opencode", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "opencode", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(),
                Map.of("MRSMITH_OPENCODE_API_KEY", "sk-env"));
        assertEquals("sk-env", catalog.resolve("a").provider().apiKey());
    }

    @Test
    void providerApiKeyEnvNameNormalizesDashes() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "my-provider", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "my-provider", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(),
                Map.of("MRSMITH_MY_PROVIDER_API_KEY", "sk-env"));
        assertEquals("sk-env", catalog.resolve("a").provider().apiKey());
    }

    @Test
    void blankProviderApiKeyEnvFallsBackToFile() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-file", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(),
                Map.of("MRSMITH_P_API_KEY", "  "));
        assertEquals("sk-file", catalog.resolve("a").provider().apiKey());
    }

    @Test
    void loadsShellCommandConfigLists() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [
                    {
                      "name": "a", "provider": "p", "model": "m",
                      "shellHarmlessCommands": ["kubectl get", "ps"],
                      "shellDangerousCommands": ["mydeploy --push"]
                    }
                  ],
                  "defaultAgent": "a"
                }
                """);
        AgentRuntime runtime = ConfigLoader.load(file, CliConfig.empty(), Map.of()).resolve("a");
        assertEquals(List.of("kubectl get", "ps"), runtime.agent().shellHarmlessCommands());
        assertEquals(List.of("mydeploy --push"), runtime.agent().shellDangerousCommands());
    }

    @Test
    void shellCommandConfigListsIgnoreMalformedEntries() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [
                    {
                      "name": "a", "provider": "p", "model": "m",
                      "shellHarmlessCommands": "kubectl",
                      "shellDangerousCommands": [null, 5, "ok"]
                    }
                  ],
                  "defaultAgent": "a"
                }
                """);
        AgentRuntime runtime = ConfigLoader.load(file, CliConfig.empty(), Map.of()).resolve("a");
        assertTrue(runtime.agent().shellHarmlessCommands().isEmpty());
        assertEquals(List.of("ok"), runtime.agent().shellDangerousCommands());
    }

    @Test
    void shellCommandConfigListsDefaultToEmpty() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentRuntime runtime = ConfigLoader.load(file, CliConfig.empty(), Map.of()).resolve("a");
        assertTrue(runtime.agent().shellHarmlessCommands().isEmpty());
        assertTrue(runtime.agent().shellDangerousCommands().isEmpty());
    }

    @Test
    void contextStrategyParseIsCaseInsensitive() {
        assertEquals(ContextStrategy.FULL, ContextStrategy.parse("full"));
        assertEquals(ContextStrategy.FULL, ContextStrategy.parse("FULL"));
        assertEquals(ContextStrategy.SLIDING, ContextStrategy.parse("sliding"));
        assertEquals(ContextStrategy.SLIDING, ContextStrategy.parse(" Sliding "));
        assertEquals(ContextStrategy.FULL, ContextStrategy.parse(null));
        assertEquals(ContextStrategy.FULL, ContextStrategy.parse("  "));
    }

    @Test
    void contextStrategyParseRejectsUnknown() {
        ConfigException e = assertThrows(ConfigException.class, () -> ContextStrategy.parse("bogus"));
        assertTrue(e.getMessage().contains("contextBuilder"));
    }

    @Test
    void contextBuilderDefaultsToFullPerAgent() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(ContextStrategy.FULL, catalog.resolve("a").agent().contextBuilder());
    }

    @Test
    void globalContextBuilderIsDefaultForAgentsWithoutOverride() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a",
                  "contextBuilder": "sliding"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(ContextStrategy.SLIDING, catalog.resolve("a").agent().contextBuilder());
    }

    @Test
    void perAgentContextBuilderOverridesGlobalDefault() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [
                    { "name": "a", "provider": "p", "model": "m", "contextBuilder": "sliding" },
                    { "name": "b", "provider": "p", "model": "m" }
                  ],
                  "defaultAgent": "a",
                  "contextBuilder": "full"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(ContextStrategy.SLIDING, catalog.resolve("a").agent().contextBuilder());
        assertEquals(ContextStrategy.FULL, catalog.resolve("b").agent().contextBuilder());
    }

    @Test
    void contextWindowRatioDefaultsAndParses() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(AgentRuntime.DEFAULT_CONTEXT_WINDOW_RATIO, catalog.resolve("a").globals().contextWindowRatio());
    }

    @Test
    void contextWindowRatioFromEnvOverridesFile() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a",
                  "contextWindowRatio": 0.5
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(),
                Map.of("MRSMITH_CONTEXT_WINDOW_RATIO", "0.8"));
        assertEquals(0.8, catalog.resolve("a").globals().contextWindowRatio());
    }

    @Test
    void invalidContextWindowRatioThrows() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a",
                  "contextWindowRatio": 1.5
                }
                """);
        assertThrows(ConfigException.class,
                () -> ConfigLoader.load(file, CliConfig.empty(), Map.of()));
    }

    @Test
    void invalidContextBuilderThrows() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m", "contextBuilder": "bogus" } ],
                  "defaultAgent": "a"
                }
                """);
        assertThrows(ConfigException.class,
                () -> ConfigLoader.load(file, CliConfig.empty(), Map.of()));
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
