package com.mrsmith.provider;

public record ChatMessage(Role role, String content) {

    public String roleName() {
        return role.apiName();
    }
}
