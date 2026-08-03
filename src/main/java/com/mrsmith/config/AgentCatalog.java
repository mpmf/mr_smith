package com.mrsmith.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AgentCatalog {

    private final Map<String, ProviderConfig> providers;
    private final Map<String, AgentConfig> agents;
    private final String defaultAgent;
    private final boolean includeUsage;
    private final Path sessionsDir;

    public AgentCatalog(List<ProviderConfig> providers, List<AgentConfig> agents,
                        String defaultAgent, boolean includeUsage, Path sessionsDir) {
        this.providers = new LinkedHashMap<>();
        for (ProviderConfig provider : providers) {
            if (this.providers.putIfAbsent(provider.name(), provider) != null) {
                throw new ConfigException("Duplicate provider name: " + provider.name());
            }
            if (provider.apiKey() == null || provider.apiKey().isBlank()
                    || provider.baseUrl() == null || provider.baseUrl().isBlank()) {
                throw new ConfigException("Provider '" + provider.name()
                        + "' is missing apiKey or baseUrl.");
            }
        }
        this.agents = new LinkedHashMap<>();
        for (AgentConfig agent : agents) {
            if (this.agents.putIfAbsent(agent.name(), agent) != null) {
                throw new ConfigException("Duplicate agent name: " + agent.name());
            }
            if (!this.providers.containsKey(agent.provider())) {
                throw new ConfigException("Agent '" + agent.name() + "' references unknown provider '"
                        + agent.provider() + "'");
            }
            if (agent.model() == null || agent.model().isBlank()) {
                throw new ConfigException("Agent '" + agent.name() + "' is missing model.");
            }
        }
        if (this.agents.isEmpty()) {
            throw new ConfigException("No agents defined in the config file.");
        }
        if (defaultAgent == null || !this.agents.containsKey(defaultAgent)) {
            throw new ConfigException("defaultAgent '" + defaultAgent + "' does not match any defined agent.");
        }
        this.defaultAgent = defaultAgent;
        this.includeUsage = includeUsage;
        this.sessionsDir = sessionsDir;
    }

    public AppConfig resolve(String agentName) {
        AgentConfig agent = agents.get(agentName);
        if (agent == null) {
            throw new ConfigException("Unknown agent: " + agentName);
        }
        ProviderConfig provider = providers.get(agent.provider());
        return new AppConfig(provider.apiKey(), provider.baseUrl(), agent.model(),
                agent.systemPrompt(), agent.maxContextTokens(), includeUsage, sessionsDir);
    }

    public String defaultName() {
        return defaultAgent;
    }

    public Set<String> agentNames() {
        return agents.keySet();
    }

    public Path sessionsDir() {
        return sessionsDir;
    }
}
