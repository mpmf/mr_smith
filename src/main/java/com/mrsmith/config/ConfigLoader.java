package com.mrsmith.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ConfigLoader {

    public static final Path DEFAULT_CONFIG_PATH = Path.of(
            System.getProperty("user.home"), ".config", "mrsmith", "config.json");

    private static final ObjectMapper JSON = new ObjectMapper();

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

        List<ProviderConfig> providers = parseProviders(root);
        List<AgentConfig> agents = parseAgents(root);
        String defaultAgent = root.hasNonNull("defaultAgent") ? root.get("defaultAgent").asText() : null;
        boolean includeUsage = !root.hasNonNull("includeUsage") || root.get("includeUsage").asBoolean();

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
                projectSkillsDir, globalSkillsDir);
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

    private static List<ProviderConfig> parseProviders(JsonNode root) {
        List<ProviderConfig> result = new ArrayList<>();
        JsonNode arr = root.path("providers");
        if (arr.isArray()) {
            for (JsonNode node : arr) {
                result.add(new ProviderConfig(
                        node.path("name").asText(),
                        node.path("apiKey").asText(null),
                        node.path("baseUrl").asText()));
            }
        }
        return result;
    }

    private static List<AgentConfig> parseAgents(JsonNode root) {
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
                        parseTools(node)));
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

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
