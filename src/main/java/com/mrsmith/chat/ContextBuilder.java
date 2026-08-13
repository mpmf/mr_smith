package com.mrsmith.chat;

import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.ToolCall;

import java.util.List;

public interface ContextBuilder {

    default void start(String systemPrompt) {
        start(systemPrompt, 0);
    }

    void start(String systemPrompt, int windowBudgetTokens);

    void appendUser(String content);

    void appendAssistant(String content);

    void appendAssistantToolCalls(List<ToolCall> toolCalls);

    void appendToolResult(String toolCallId, String content);

    void appendSystem(String content);

    List<ChatMessage> messages();
}
