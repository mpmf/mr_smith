package com.mrsmith.config;

import java.nio.file.Path;

public record CliConfig(String agent, Path sessionsDir, String contextBuilder, Double contextWindowRatio) {

    public CliConfig(String agent, Path sessionsDir) {
        this(agent, sessionsDir, null, null);
    }

    public static CliConfig empty() {
        return new CliConfig(null, null, null, null);
    }
}
