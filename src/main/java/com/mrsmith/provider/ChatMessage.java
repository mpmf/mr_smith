package com.mrsmith.provider;

import java.util.List;

public record ChatMessage(Role role, String content, String thinking,
                          List<ToolCall> toolCalls, String toolCallId) {

    public ChatMessage(Role role, String content) {
        this(role, content, null, null, null);
    }

    public ChatMessage(Role role, String content, String thinking) {
        this(role, content, thinking, null, null);
    }

    public String roleName() {
        return role.apiName();
    }
}
