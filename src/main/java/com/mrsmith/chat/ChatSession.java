package com.mrsmith.chat;

import com.mrsmith.config.AgentCatalog;
import com.mrsmith.config.AppConfig;
import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderException;
import com.mrsmith.provider.ProviderFactory;
import com.mrsmith.provider.ProviderResponse;
import com.mrsmith.provider.Role;
import com.mrsmith.provider.Usage;
import com.mrsmith.session.TranscriptWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class ChatSession {

    private static final int WARN_THRESHOLD_PERCENT = 85;
    private static final int LIMIT_PERCENT = 100;

    private final IO io;
    private final TranscriptWriter transcripts;
    private final ContextBuilder contextBuilder;
    private final AgentCatalog agents;
    private final ProviderFactory providerFactory;
    private final String initialAgentName;

    private final List<ChatMessage> history = new ArrayList<>();
    private final UsageTracker tracker = new UsageTracker();
    private boolean warned85;
    private boolean warned100;
    private UUID currentSessionId;
    private String currentAgentName;
    private AppConfig config;
    private Provider provider;

    public ChatSession(IO io, TranscriptWriter transcripts, ContextBuilder contextBuilder,
                       AgentCatalog agents, ProviderFactory providerFactory, String initialAgentName) {
        this.io = io;
        this.transcripts = transcripts;
        this.contextBuilder = contextBuilder;
        this.agents = agents;
        this.providerFactory = providerFactory;
        this.initialAgentName = initialAgentName;
    }

    public void run() throws IOException {
        io.writeLine("Mr Smith. Type /help for commands, /exit to quit.");
        currentAgentName = initialAgentName;
        applyAgent();
        io.writeLine("Agent: " + currentAgentName);
        startFreshSession();
        String line;
        while ((line = io.readLine()) != null) {
            if (line.equals("/exit")) {
                break;
            }
            if (handleCommand(line)) {
                continue;
            }
            history.add(new ChatMessage(Role.USER, line));
            appendUser(line);
            contextBuilder.appendUser(line);
            try {
                List<ChatMessage> context = contextBuilder.messages();
                ProviderResponse response = provider.send(context, io::write, io::writeReasoning);
                history.add(response.message());
                contextBuilder.appendAssistant(response.message().content());
                appendAssistant(response.message().content(), response.message().thinking(),
                        response.usage(), response.usageEstimated());
                io.writeLine("");
                tracker.recordTurn(response.usage(), response.usageEstimated());
                String usageLine = tracker.lastTurnLine();
                if (!usageLine.isEmpty()) {
                    io.writeLine(usageLine);
                }
                warnIfNearLimit();
            } catch (ProviderException e) {
                if (e.hasPartialContent() || e.partialThinking() != null) {
                    history.add(new ChatMessage(Role.ASSISTANT, e.partialContent(), e.partialThinking()));
                    contextBuilder.appendAssistant(e.partialContent());
                    appendAssistant(e.partialContent(), e.partialThinking(), null, false);
                }
                io.writeLine("");
                io.writeLine("Error: " + e.getMessage());
            } catch (RuntimeException e) {
                io.writeLine("");
                io.writeLine("Error: " + e.getMessage());
            }
        }
    }

    private void applyAgent() {
        config = agents.resolve(currentAgentName);
        provider = providerFactory.create(config);
    }

    private void startFreshSession() {
        history.clear();
        tracker.reset();
        warned85 = false;
        warned100 = false;
        contextBuilder.start(config.systemPrompt());
        startNewSession();
    }

    private void startNewSession() {
        UUID id = UUID.randomUUID();
        try {
            transcripts.start(id);
            currentSessionId = id;
        } catch (IOException e) {
            currentSessionId = null;
            System.err.println("Warning: could not create session folder for " + id
                    + ": " + e.getMessage() + ". Session transcript disabled.");
            return;
        }
        io.writeLine("Session: " + id);
    }

    private void switchAgent(String name) {
        if (!agents.agentNames().contains(name)) {
            io.writeLine("Unknown agent: " + name);
            return;
        }
        currentAgentName = name;
        applyAgent();
        io.writeLine("Agent: " + name);
        startFreshSession();
    }

    private void appendUser(String content) {
        if (currentSessionId == null) {
            return;
        }
        try {
            transcripts.appendUser(currentSessionId, content);
        } catch (IOException e) {
            System.err.println("Warning: could not write session transcript: " + e.getMessage());
            currentSessionId = null;
        }
    }

    private void appendAssistant(String content, String thinking, Usage usage, boolean estimated) {
        if (currentSessionId == null) {
            return;
        }
        try {
            transcripts.appendAssistant(currentSessionId, content, thinking, usage, estimated);
        } catch (IOException e) {
            System.err.println("Warning: could not write session transcript: " + e.getMessage());
            currentSessionId = null;
        }
    }

    private void warnIfNearLimit() {
        if (!contextLimitConfigured()) {
            return;
        }
        int pct = pctOfMax();
        if (pct >= LIMIT_PERCENT) {
            if (!warned100) {
                warned100 = true;
                io.writeLine(String.format(Locale.US,
                        "Warning: session reached 100%% of your configured %,d-token context limit — consider /reset",
                        config.maxContextTokens()));
            }
        } else if (pct >= WARN_THRESHOLD_PERCENT) {
            if (!warned85) {
                warned85 = true;
                io.writeLine(String.format(Locale.US,
                        "Warning: session at %d%% of your configured %,d-token context limit — consider /reset",
                        pct, config.maxContextTokens()));
            }
        }
    }

    private boolean contextLimitConfigured() {
        Integer maxContext = config.maxContextTokens();
        return maxContext != null && maxContext > 0;
    }

    private int pctOfMax() {
        return (int) Math.round(tracker.totalTokens() * 100.0 / config.maxContextTokens());
    }

    private boolean handleCommand(String line) {
        if (!line.startsWith("/")) {
            return false;
        }
        if (line.startsWith("/agent ")) {
            switchAgent(line.substring("/agent ".length()).trim());
            return true;
        }
        switch (line) {
            case "/reset" -> {
                startFreshSession();
                io.writeLine("History cleared.");
            }
            case "/agents" -> io.writeLine("Agents: " + String.join(", ", agents.agentNames()));
            case "/usage" -> io.writeLine(usageReport());
            case "/help" -> io.writeLine("Commands: /exit, /reset, /help, /usage, /agents, /agent <name>. Anything else is sent to the LLM.");
            default -> io.writeLine("Unknown command: " + line + " (type /help)");
        }
        return true;
    }

    private String usageReport() {
        StringBuilder report = new StringBuilder(tracker.usageReport());
        if (contextLimitConfigured()) {
            report.append(String.format(Locale.US, "%n  context limit: %,d configured (%d%% used)",
                    config.maxContextTokens(), pctOfMax()));
        }
        report.append(String.format(Locale.US, "%n  history: %d messages", history.size()));
        return report.toString();
    }
}
