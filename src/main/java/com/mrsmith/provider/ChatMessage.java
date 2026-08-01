package com.mrsmith.provider;

public record ChatMessage(Role role, String content, String thinking) {

    public ChatMessage(Role role, String content) {
        this(role, content, null);
    }

    public String roleName() {
        return role.apiName();
    }
}
