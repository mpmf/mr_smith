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

    public static AppConfig load(CliConfig cli) {
        return load(DEFAULT_CONFIG_PATH, cli, System.getenv());
    }

    public static AppConfig load(Path configFile, CliConfig cli, Map<String, String> env) {
        String fileModel = null;
        String fileBaseUrl = null;
        String fileSystemPrompt = null;
        String fileApiKey = null;
        Integer fileMaxContext = null;
        Boolean fileIncludeUsage = null;

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
                if (root.hasNonNull("maxContextTokens")) {
                    fileMaxContext = root.get("maxContextTokens").asInt();
                }
                if (root.hasNonNull("includeUsage")) {
                    fileIncludeUsage = root.get("includeUsage").asBoolean();
                }
            } catch (IOException e) {
                System.err.println("Warning: could not read config file " + configFile
                        + " (" + e.getMessage() + "). Falling back to env vars and defaults.");
            }
        }

        String model = firstNonNull(cli.model(), env.get("MRSMITH_MODEL"), fileModel, "gpt-4o-mini");
        String baseUrl = firstNonNull(cli.baseUrl(), env.get("MRSMITH_BASE_URL"), fileBaseUrl,
                "https://api.openai.com/v1");
        String systemPrompt = firstNonNull(cli.systemPrompt(), fileSystemPrompt);
        String apiKey = firstNonNull(cli.apiKey(), env.get("OPENAI_API_KEY"), fileApiKey);

        Integer maxContext = firstNonNullValue(cli.maxContextTokens(),
                parseEnvInt(env.get("MRSMITH_MAX_CONTEXT")), fileMaxContext);
        Boolean includeUsage = firstNonNullValue(cli.includeUsage(),
                parseEnvBool(env.get("MRSMITH_INCLUDE_USAGE")), fileIncludeUsage);

        if (apiKey == null) {
            throw new ConfigException(
                    "OPENAI_API_KEY is not set. Export it as an environment variable "
                            + "(e.g. export OPENAI_API_KEY=sk-...) or pass it with --api-key.");
        }

        return new AppConfig(apiKey, baseUrl, model, systemPrompt,
                maxContext, includeUsage == null || includeUsage);
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static <T> T firstNonNullValue(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer parseEnvInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean parseEnvBool(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Boolean.parseBoolean(value);
    }
}
