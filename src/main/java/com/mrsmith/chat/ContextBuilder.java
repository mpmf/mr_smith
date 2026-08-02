package com.mrsmith.chat;

import com.mrsmith.provider.ChatMessage;

import java.util.List;

public interface ContextBuilder {

    void start(String systemPrompt);

    void appendUser(String content);

    void appendAssistant(String content);

    List<ChatMessage> messages();
}
