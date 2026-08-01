package com.mrsmith.chat;

import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderException;
import com.mrsmith.provider.Role;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ChatSession {

    private final Provider provider;
    private final IO io;
    private final List<ChatMessage> history = new ArrayList<>();

    public ChatSession(Provider provider, IO io) {
        this.provider = provider;
        this.io = io;
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
                ChatMessage reply = provider.send(history, io::write).message();
                history.add(reply);
                io.writeLine("");
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

    private boolean handleCommand(String line) {
        if (!line.startsWith("/")) {
            return false;
        }
        switch (line) {
            case "/reset" -> {
                history.clear();
                io.writeLine("History cleared.");
            }
            case "/help" -> io.writeLine("Commands: /exit, /reset, /help. Anything else is sent to the LLM.");
            default -> io.writeLine("Unknown command: " + line + " (type /help)");
        }
        return true;
    }
}
