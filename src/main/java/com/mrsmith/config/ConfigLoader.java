package com.mrsmith.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class ConfigLoader {

    public static final Path DEFAULT_CONFIG_PATH = Path.of(
            System.getProperty("user.home"), ".config", "mrsmith", "config.json");

    private static final ObjectMapper JSON = new ObjectMapper();

    private ConfigLoader() {
    }

    public static AppConfig load(String cliModel, String cliBaseUrl, String cliSystemPrompt, String cliApiKey) {
        return load(DEFAULT_CONFIG_PATH, cliModel, cliBaseUrl, cliSystemPrompt, cliApiKey, System.getenv());
    }

    public static AppConfig load(Path configFile, String cliModel, String cliBaseUrl,
                                 String cliSystemPrompt, String cliApiKey, Map<String, String> env) {
        String fileModel = null;
        String fileBaseUrl = null;
        String fileSystemPrompt = null;
        String fileApiKey = null;

        if (Files.exists(configFile)) {
            try {
                JsonNode root = JSON.readTree(configFile.toFile());
                if (root.hasNonNull("model")) {
                    fileModel = root.get("model").asText();
                }
                if (root.hasNonNull("baseUrl")) {
                    fileBaseUrl = root.get("baseUrl").asText();
                }
                if (root.hasNonNull("systemPrompt")) {
                    fileSystemPrompt = root.get("systemPrompt").asText();
                }
                if (root.hasNonNull("apiKey")) {
                    fileApiKey = root.get("apiKey").asText();
                }
            } catch (IOException e) {
                System.err.println("Warning: could not read config file " + configFile
                        + " (" + e.getMessage() + "). Falling back to env vars and defaults.");
            }
        }

        String model = firstNonNull(cliModel, env.get("MRSMITH_MODEL"), fileModel, "gpt-4o-mini");
        String baseUrl = firstNonNull(cliBaseUrl, env.get("MRSMITH_BASE_URL"), fileBaseUrl,
                "https://api.openai.com/v1");
        String systemPrompt = firstNonNull(cliSystemPrompt, fileSystemPrompt);
        String apiKey = firstNonNull(cliApiKey, env.get("OPENAI_API_KEY"), fileApiKey);

        if (apiKey == null) {
            throw new ConfigException(
                    "OPENAI_API_KEY is not set. Export it as an environment variable "
                            + "(e.g. export OPENAI_API_KEY=sk-...) or pass it with --api-key.");
        }

        return new AppConfig(apiKey, baseUrl, model, systemPrompt);
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
