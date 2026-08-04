package com.mrsmith.provider;

public enum Role {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    private final String apiName;

    Role(String apiName) {
        this.apiName = apiName;
    }

    public String apiName() {
        return apiName;
    }
}
