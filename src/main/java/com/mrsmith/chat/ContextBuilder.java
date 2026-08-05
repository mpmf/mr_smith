package com.mrsmith.chat;

import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.ToolCall;

import java.util.List;

public interface ContextBuilder {

    void start(String systemPrompt);

    void appendUser(String content);

    void appendAssistant(String content);

    void appendAssistantToolCalls(List<ToolCall> toolCalls);

    void appendToolResult(String toolCallId, String content);

    List<ChatMessage> messages();
}
