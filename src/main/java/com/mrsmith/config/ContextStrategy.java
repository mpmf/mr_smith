package com.mrsmith.config;

import java.util.Locale;

public enum ContextStrategy {
    FULL, SLIDING;

    public static ContextStrategy parse(String value) {
        if (value == null || value.isBlank()) {
            return FULL;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "full" -> FULL;
            case "sliding" -> SLIDING;
            default -> throw new ConfigException("Unknown contextBuilder: " + value
                    + " (expected \"full\" or \"sliding\")");
        };
    }
}
