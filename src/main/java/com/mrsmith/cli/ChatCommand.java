package com.mrsmith.cli;

import com.mrsmith.chat.ChatSession;
import com.mrsmith.chat.ContextBuilder;
import com.mrsmith.chat.FullContextBuilder;
import com.mrsmith.config.AgentCatalog;
import com.mrsmith.config.ConfigException;
import com.mrsmith.config.ConfigLoader;
import com.mrsmith.config.CliConfig;
import com.mrsmith.io.IO;
import com.mrsmith.io.ReplIo;
import com.mrsmith.provider.OpenAiCompatibleProvider;
import com.mrsmith.session.FileTranscriptWriter;
import com.mrsmith.session.TranscriptWriter;
import com.mrsmith.tool.ToolRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "mrsmith", mixinStandardHelpOptions = true,
        description = "Interactive chat against any OpenAI-compatible endpoint.")
public class ChatCommand implements Callable<Integer> {

    @Option(names = "--agent", description = "Agent to use (overrides the default agent).")
    private String agent;

    @Option(names = "--sessions-dir", description = "Directory where session transcripts are stored (overrides config file and env).")
    private Path sessionsDir;

    @Override
    public Integer call() {
        AgentCatalog catalog;
        try {
            catalog = ConfigLoader.load(new CliConfig(agent, sessionsDir));
        } catch (ConfigException e) {
            System.err.println(e.getMessage());
            return 1;
        }

        String initialAgent = agent != null ? agent : catalog.defaultName();
        try {
            catalog.resolve(initialAgent);
        } catch (ConfigException e) {
            System.err.println(e.getMessage());
            return 1;
        }
        IO io = new ReplIo();
        TranscriptWriter transcripts = new FileTranscriptWriter(catalog.sessionsDir());
        ContextBuilder contextBuilder = new FullContextBuilder();
        ChatSession session = new ChatSession(io, transcripts, contextBuilder, catalog,
                OpenAiCompatibleProvider::new, config -> ToolRegistry.with(config.tools()), initialAgent);
        try {
            session.run();
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
        return 0;
    }
}
