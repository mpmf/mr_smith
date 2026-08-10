package com.mrsmith.config;

import java.util.List;

/** Config extension points for shell command classification (empty = use built-in defaults). */
public record ShellConfig(List<String> harmlessCommands, List<String> dangerousCommands) {

    public static ShellConfig empty() {
        return new ShellConfig(List.of(), List.of());
    }
}
