package com.mrsmith.cli;

import com.mrsmith.chat.ChatSession;
import com.mrsmith.config.AppConfig;
import com.mrsmith.config.CliConfig;
import com.mrsmith.config.ConfigException;
import com.mrsmith.config.ConfigLoader;
import com.mrsmith.io.IO;
import com.mrsmith.io.ReplIo;
import com.mrsmith.provider.OpenAiCompatibleProvider;
import com.mrsmith.provider.Provider;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.util.concurrent.Callable;

@Command(name = "mrsmith", mixinStandardHelpOptions = true,
        description = "Interactive chat against any OpenAI-compatible endpoint.")
public class ChatCommand implements Callable<Integer> {

    @Option(names = "--model", description = "Model to use (overrides config file and env).")
    private String model;

    @Option(names = "--base-url", description = "Provider base URL, e.g. https://api.openai.com/v1")
    private String baseUrl;

    @Option(names = "--system-prompt", description = "Optional system prompt.")
    private String systemPrompt;

    @Option(names = "--api-key", description = "API key (overrides OPENAI_API_KEY).")
    private String apiKey;

    @Option(names = "--max-context", description = "Context window token limit (overrides config file and env).")
    private Integer maxContext;

    @Option(names = "--include-usage", description = "Request usage stats from the provider (default true).")
    private Boolean includeUsage;

    @Override
    public Integer call() {
        AppConfig config;
        try {
            config = ConfigLoader.load(
                    new CliConfig(apiKey, baseUrl, model, systemPrompt, maxContext, includeUsage));
        } catch (ConfigException e) {
            System.err.println(e.getMessage());
            return 1;
        }

        Provider provider = new OpenAiCompatibleProvider(config);
        IO io = new ReplIo();
        ChatSession session = new ChatSession(provider, io);
        try {
            session.run();
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
        return 0;
    }
}
