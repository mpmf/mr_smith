package com.mrsmith.config;

import java.nio.file.Path;

public record CliConfig(String agent, Path sessionsDir) {

    public static CliConfig empty() {
        return new CliConfig(null, null);
    }
}
