package com.mrsmith.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void missingApiKeyFailsFast() {
        assertThrows(ConfigException.class,
                () -> ConfigLoader.load(noFile(), CliConfig.empty(), Map.of()));
    }

    @Test
    void defaultsWhenNoFileEnvOrCli() {
        AppConfig config = ConfigLoader.load(noFile(), CliConfig.empty(), Map.of("OPENAI_API_KEY", "sk-x"));
        assertEquals("gpt-4o-mini", config.model());
        assertEquals("https://api.openai.com/v1", config.baseUrl());
        assertNull(config.systemPrompt());
        assertNull(config.maxContextTokens());
        assertTrue(config.includeUsage());
    }

    @Test
    void envApiKeyIsAccepted() {
        AppConfig config = ConfigLoader.load(noFile(), CliConfig.empty(), Map.of("OPENAI_API_KEY", "sk-env"));
        assertEquals("sk-env", config.apiKey());
    }

    @Test
    void fileApiKeyIsUsedWhenEnvAndCliAbsent() throws IOException {
        Path file = writeConfig("{ \"apiKey\": \"sk-file\" }");
        AppConfig config = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals("sk-file", config.apiKey());
    }

    @Test
    void envOverridesFileApiKey() throws IOException {
        Path file = writeConfig("{ \"apiKey\": \"sk-file\" }");
        AppConfig config = ConfigLoader.load(file, CliConfig.empty(), Map.of("OPENAI_API_KEY", "sk-env"));
        assertEquals("sk-env", config.apiKey());
    }

    @Test
    void envOverridesFile() throws IOException {
        Path file = writeConfig("{ \"model\": \"from-file\", \"baseUrl\": \"https://file.example\", \"systemPrompt\": \"file prompt\" }");
        AppConfig config = ConfigLoader.load(file, CliConfig.empty(),
                Map.of("OPENAI_API_KEY", "sk-env", "MRSMITH_MODEL", "from-env"));
        assertEquals("from-env", config.model());
        assertEquals("https://file.example", config.baseUrl());
        assertEquals("file prompt", config.systemPrompt());
    }

    @Test
    void cliOverridesEnv() throws IOException {
        Path file = writeConfig("{ \"model\": \"from-file\" }");
        CliConfig cli = new CliConfig("sk-cli", null, "from-cli", null, null, null);
        AppConfig config = ConfigLoader.load(file, cli,
                Map.of("OPENAI_API_KEY", "sk-env", "MRSMITH_MODEL", "from-env"));
        assertEquals("from-cli", config.model());
        assertEquals("sk-cli", config.apiKey());
    }

    @Test
    void malformedFileFallsBackToDefaults() throws IOException {
        Path file = writeConfig("not valid json {{{");
        AppConfig config = ConfigLoader.load(file, CliConfig.empty(), Map.of("OPENAI_API_KEY", "sk-x"));
        assertEquals("gpt-4o-mini", config.model());
        assertEquals("sk-x", config.apiKey());
    }

    @Test
    void baseUrlTrailingSlashIsStripped() throws IOException {
        Path file = writeConfig("{ \"baseUrl\": \"https://example.com/v1/\" }");
        AppConfig config = ConfigLoader.load(file, CliConfig.empty(), Map.of("OPENAI_API_KEY", "sk-x"));
        assertEquals("https://example.com/v1", config.baseUrl());
    }

    @Test
    void maxContextAndIncludeUsageReadFromFile() throws IOException {
        Path file = writeConfig("{ \"maxContextTokens\": 128000, \"includeUsage\": false }");
        AppConfig config = ConfigLoader.load(file, CliConfig.empty(), Map.of("OPENAI_API_KEY", "sk-x"));
        assertEquals(128000, config.maxContextTokens());
        assertFalse(config.includeUsage());
    }

    @Test
    void cliOverridesFileForMaxContextAndIncludeUsage() throws IOException {
        Path file = writeConfig("{ \"maxContextTokens\": 128000, \"includeUsage\": false }");
        CliConfig cli = new CliConfig(null, null, null, null, 8192, true);
        AppConfig config = ConfigLoader.load(file, cli, Map.of("OPENAI_API_KEY", "sk-x"));
        assertEquals(8192, config.maxContextTokens());
        assertTrue(config.includeUsage());
    }

    @Test
    void envProvidesMaxContextAndIncludeUsage() throws IOException {
        AppConfig config = ConfigLoader.load(noFile(), CliConfig.empty(),
                Map.of("OPENAI_API_KEY", "sk-x", "MRSMITH_MAX_CONTEXT", "8192", "MRSMITH_INCLUDE_USAGE", "false"));
        assertEquals(8192, config.maxContextTokens());
        assertFalse(config.includeUsage());
    }

    @Test
    void invalidEnvMaxContextIsIgnored() throws IOException {
        AppConfig config = ConfigLoader.load(noFile(), CliConfig.empty(),
                Map.of("OPENAI_API_KEY", "sk-x", "MRSMITH_MAX_CONTEXT", "abc"));
        assertNull(config.maxContextTokens());
    }

    @Test
    void invalidEnvIncludeUsageIsIgnored() throws IOException {
        AppConfig config = ConfigLoader.load(noFile(), CliConfig.empty(),
                Map.of("OPENAI_API_KEY", "sk-x", "MRSMITH_INCLUDE_USAGE", "treu"));
        assertTrue(config.includeUsage());
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
