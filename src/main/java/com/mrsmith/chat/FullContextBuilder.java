package com.mrsmith.chat;

import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Role;

import java.util.ArrayList;
import java.util.List;

public class FullContextBuilder implements ContextBuilder {

    private final List<ChatMessage> context = new ArrayList<>();

    @Override
    public void start(String systemPrompt) {
        context.clear();
        if (systemPrompt != null) {
            context.add(new ChatMessage(Role.SYSTEM, systemPrompt));
        }
    }

    @Override
    public void appendUser(String content) {
        context.add(new ChatMessage(Role.USER, content));
    }

    @Override
    public void appendAssistant(String content) {
        context.add(new ChatMessage(Role.ASSISTANT, content));
    }

    @Override
    public List<ChatMessage> messages() {
        return List.copyOf(context);
    }
}
