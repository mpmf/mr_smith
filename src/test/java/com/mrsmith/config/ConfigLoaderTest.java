package com.mrsmith.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void missingApiKeyFailsFast() {
        assertThrows(ConfigException.class,
                () -> ConfigLoader.load(noFile(), null, null, null, null, Map.of()));
    }

    @Test
    void defaultsWhenNoFileEnvOrCli() {
        AppConfig config = ConfigLoader.load(noFile(), null, null, null, null,
                Map.of("OPENAI_API_KEY", "sk-x"));
        assertEquals("gpt-4o-mini", config.model());
        assertEquals("https://api.openai.com/v1", config.baseUrl());
        assertNull(config.systemPrompt());
    }

    @Test
    void envApiKeyIsAccepted() {
        AppConfig config = ConfigLoader.load(noFile(), null, null, null, null,
                Map.of("OPENAI_API_KEY", "sk-env"));
        assertEquals("sk-env", config.apiKey());
    }

    @Test
    void envOverridesFile() throws IOException {
        Path file = writeConfig("{ \"model\": \"from-file\", \"baseUrl\": \"https://file.example\", \"systemPrompt\": \"file prompt\" }");
        AppConfig config = ConfigLoader.load(file, null, null, null, null,
                Map.of("OPENAI_API_KEY", "sk-env", "MRSMITH_MODEL", "from-env"));
        assertEquals("from-env", config.model());
        assertEquals("https://file.example", config.baseUrl());
        assertEquals("file prompt", config.systemPrompt());
    }

    @Test
    void cliOverridesEnv() throws IOException {
        Path file = writeConfig("{ \"model\": \"from-file\" }");
        AppConfig config = ConfigLoader.load(file, "from-cli", null, null, "sk-cli",
                Map.of("OPENAI_API_KEY", "sk-env", "MRSMITH_MODEL", "from-env"));
        assertEquals("from-cli", config.model());
        assertEquals("sk-cli", config.apiKey());
    }

    @Test
    void malformedFileFallsBackToDefaults() throws IOException {
        Path file = writeConfig("not valid json {{{");
        AppConfig config = ConfigLoader.load(file, null, null, null, "sk-x", Map.of());
        assertEquals("gpt-4o-mini", config.model());
        assertEquals("sk-x", config.apiKey());
    }

    @Test
    void baseUrlTrailingSlashIsStripped() throws IOException {
        Path file = writeConfig("{ \"baseUrl\": \"https://example.com/v1/\" }");
        AppConfig config = ConfigLoader.load(file, null, null, null, "sk-x", Map.of());
        assertEquals("https://example.com/v1", config.baseUrl());
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
