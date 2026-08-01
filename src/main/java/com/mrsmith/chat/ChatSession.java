package com.mrsmith.chat;

import com.mrsmith.config.AppConfig;
import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderException;
import com.mrsmith.provider.ProviderResponse;
import com.mrsmith.provider.Role;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ChatSession {

    private final Provider provider;
    private final IO io;
    private final AppConfig config;
    private final List<ChatMessage> history = new ArrayList<>();
    private final UsageTracker tracker = new UsageTracker();
    private boolean warned85;
    private boolean warned100;

    public ChatSession(Provider provider, IO io, AppConfig config) {
        this.provider = provider;
        this.io = io;
        this.config = config;
    }

    public void run() throws IOException {
        io.writeLine("Mr Smith. Type /help for commands, /exit to quit.");
        String line;
        while ((line = io.readLine()) != null) {
            if (line.equals("/exit")) {
                break;
            }
            if (handleCommand(line)) {
                continue;
            }
            history.add(new ChatMessage(Role.USER, line));
            try {
                ProviderResponse response = provider.send(history, io::write);
                history.add(response.message());
                io.writeLine("");
                tracker.recordTurn(response.usage(), response.usageEstimated());
                io.writeLine(tracker.lastTurnLine());
                warnIfNearLimit();
            } catch (ProviderException e) {
                if (e.hasPartialContent()) {
                    history.add(new ChatMessage(Role.ASSISTANT, e.partialContent()));
                }
                io.writeLine("");
                io.writeLine("Error: " + e.getMessage());
            } catch (RuntimeException e) {
                io.writeLine("");
                io.writeLine("Error: " + e.getMessage());
            }
        }
    }

    private void warnIfNearLimit() {
        Integer maxContext = config.maxContextTokens();
        if (maxContext == null || maxContext <= 0) {
            return;
        }
        int pct = (int) Math.round(tracker.totalTokens() * 100.0 / maxContext);
        if (pct >= 100) {
            if (!warned100) {
                warned100 = true;
                io.writeLine(String.format(Locale.US,
                        "Warning: session reached 100%% of your configured %,d-token context limit — consider /reset",
                        maxContext));
            }
        } else if (pct >= 85) {
            if (!warned85) {
                warned85 = true;
                io.writeLine(String.format(Locale.US,
                        "Warning: session at %d%% of your configured %,d-token context limit — consider /reset",
                        pct, maxContext));
            }
        }
    }

    private boolean handleCommand(String line) {
        if (!line.startsWith("/")) {
            return false;
        }
        switch (line) {
            case "/reset" -> {
                history.clear();
                tracker.reset();
                warned85 = false;
                warned100 = false;
                io.writeLine("History cleared.");
            }
            case "/usage" -> io.writeLine(usageReport());
            case "/help" -> io.writeLine("Commands: /exit, /reset, /help, /usage. Anything else is sent to the LLM.");
            default -> io.writeLine("Unknown command: " + line + " (type /help)");
        }
        return true;
    }

    private String usageReport() {
        StringBuilder report = new StringBuilder(tracker.usageReport());
        Integer maxContext = config.maxContextTokens();
        if (maxContext != null && maxContext > 0) {
            int pct = (int) Math.round(tracker.totalTokens() * 100.0 / maxContext);
            report.append(String.format(Locale.US, "%n  context limit: %,d configured (%d%% used)",
                    maxContext, pct));
        }
        report.append(String.format(Locale.US, "%n  history: %d messages", history.size()));
        return report.toString();
    }
}
