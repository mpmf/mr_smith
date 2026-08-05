package com.mrsmith.chat;

import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Role;
import com.mrsmith.provider.ToolCall;

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
    public void appendAssistantToolCalls(List<ToolCall> toolCalls) {
        context.add(new ChatMessage(Role.ASSISTANT, null, null, List.copyOf(toolCalls), null));
    }

    @Override
    public void appendToolResult(String toolCallId, String content) {
        context.add(new ChatMessage(Role.TOOL, content, null, null, toolCallId));
    }

    @Override
    public void appendSystem(String content) {
        context.add(new ChatMessage(Role.SYSTEM, content));
    }

    @Override
    public List<ChatMessage> messages() {
        return List.copyOf(context);
    }
}
