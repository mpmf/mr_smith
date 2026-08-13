package com.mrsmith.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrsmith.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ConfigLoader {

    public static final Path DEFAULT_CONFIG_PATH = Path.of(
            System.getProperty("user.home"), ".config", "mrsmith", "config.json");

    private static final ObjectMapper JSON = Json.MAPPER;

    private ConfigLoader() {
    }

    public static AgentCatalog load(CliConfig cli) {
        return load(DEFAULT_CONFIG_PATH, cli, System.getenv());
    }

    public static AgentCatalog load(Path configFile, CliConfig cli, Map<String, String> env) {
        if (!Files.exists(configFile)) {
            throw new ConfigException("No config file at " + configFile
                    + ". Create one defining providers and agents.");
        }
        JsonNode root;
        try {
            root = JSON.readTree(configFile.toFile());
        } catch (IOException e) {
            throw new ConfigException("Could not read config file " + configFile + ": " + e.getMessage());
        }

        List<ProviderConfig> providers = parseProviders(root, env);
        ContextStrategy defaultStrategy = ContextStrategy.parse(firstNonNull(
                cli.contextBuilder(),
                env.get("MRSMITH_CONTEXT_BUILDER"),
                root.hasNonNull("contextBuilder") ? root.get("contextBuilder").asText() : null));
        List<AgentConfig> agents = parseAgents(root, defaultStrategy);
        String defaultAgent = root.hasNonNull("defaultAgent") ? root.get("defaultAgent").asText() : null;
        boolean includeUsage = !root.hasNonNull("includeUsage") || root.get("includeUsage").asBoolean();
        double contextWindowRatio = parseRatio(firstNonNull(
                cli.contextWindowRatio() == null ? null : cli.contextWindowRatio().toString(),
                env.get("MRSMITH_CONTEXT_WINDOW_RATIO"),
                root.hasNonNull("contextWindowRatio") ? root.get("contextWindowRatio").asText() : null));

        String sessionsDir = firstNonNull(
                cli.sessionsDir() == null ? null : cli.sessionsDir().toString(),
                env.get("MRSMITH_SESSIONS_DIR"),
                root.hasNonNull("sessionsDir") ? root.get("sessionsDir").asText() : null,
                Path.of(System.getProperty("user.home"), ".config", "mrsmith", "sessions").toString());

        Path projectSkillsDir = skillDir(
                root.hasNonNull("projectSkillsDir") ? root.get("projectSkillsDir").asText() : null,
                System.getProperty("user.dir"), AgentCatalog.defaultProjectSkillsDir());
        Path globalSkillsDir = skillDir(
                root.hasNonNull("globalSkillsDir") ? root.get("globalSkillsDir").asText() : null,
                System.getProperty("user.home"), AgentCatalog.defaultGlobalSkillsDir());

        return new AgentCatalog(providers, agents, defaultAgent, includeUsage, Path.of(sessionsDir),
                projectSkillsDir, globalSkillsDir, contextWindowRatio);
    }

    private static Path skillDir(String configured, String anchor, Path defaultPath) {
        if (configured == null || configured.isBlank()) {
            return defaultPath;
        }
        Path path = Path.of(configured);
        if (path.isAbsolute()) {
            return path;
        }
        return Path.of(anchor).resolve(path);
    }

    private static List<ProviderConfig> parseProviders(JsonNode root, Map<String, String> env) {
        List<ProviderConfig> result = new ArrayList<>();
        JsonNode arr = root.path("providers");
        if (arr.isArray()) {
            for (JsonNode node : arr) {
                String name = node.path("name").asText();
                String apiKey = node.path("apiKey").asText(null);
                String envKey = env.get("MRSMITH_" + normalizeEnvName(name) + "_API_KEY");
                if (envKey != null && !envKey.isBlank()) {
                    apiKey = envKey;
                }
                result.add(new ProviderConfig(name, apiKey, node.path("baseUrl").asText()));
            }
        }
        return result;
    }

    private static String normalizeEnvName(String name) {
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    private static List<AgentConfig> parseAgents(JsonNode root, ContextStrategy defaultStrategy) {
        List<AgentConfig> result = new ArrayList<>();
        JsonNode arr = root.path("agents");
        if (arr.isArray()) {
            for (JsonNode node : arr) {
                result.add(new AgentConfig(
                        node.path("name").asText(),
                        node.path("provider").asText(),
                        node.path("model").asText(null),
                        node.path("systemPrompt").asText(null),
                        node.hasNonNull("maxContextTokens") ? node.get("maxContextTokens").asInt() : null,
                        node.hasNonNull("maxToolRounds") ? node.get("maxToolRounds").asInt() : null,
                        node.hasNonNull("maxToolCallsPerSession") ? node.get("maxToolCallsPerSession").asInt() : null,
                        parseTools(node),
                        parseStringList(node, "shellHarmlessCommands"),
                        parseStringList(node, "shellDangerousCommands"),
                        node.hasNonNull("contextBuilder")
                                ? ContextStrategy.parse(node.get("contextBuilder").asText())
                                : defaultStrategy));
            }
        }
        return result;
    }

    private static List<String> parseTools(JsonNode agentNode) {
        List<String> tools = new ArrayList<>();
        JsonNode arr = agentNode.path("tools");
        if (arr.isArray()) {
            for (JsonNode tool : arr) {
                tools.add(tool.asText());
            }
        }
        return tools;
    }

    private static List<String> parseStringList(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        JsonNode arr = node.path(field);
        if (arr.isArray()) {
            for (JsonNode item : arr) {
                if (item != null && item.isTextual() && !item.asText().isBlank()) {
                    result.add(item.asText());
                }
            }
        }
        return result;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static double parseRatio(String raw) {
        if (raw == null || raw.isBlank()) {
            return AgentRuntime.DEFAULT_CONTEXT_WINDOW_RATIO;
        }
        double value;
        try {
            value = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("Invalid contextWindowRatio: " + raw);
        }
        if (!Double.isFinite(value) || value <= 0 || value > 1) {
            throw new ConfigException("contextWindowRatio must be in (0, 1]: " + raw);
        }
        return value;
    }
}
