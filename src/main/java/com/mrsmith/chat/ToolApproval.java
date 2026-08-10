package com.mrsmith.chat;

import java.util.HashSet;
import java.util.Set;

/** Session-scoped record of tool names the user approved to run without prompting. */
public final class ToolApproval {

    private final Set<String> alwaysAllowed = new HashSet<>();

    public boolean isAlwaysAllowed(String toolName) {
        return alwaysAllowed.contains(toolName);
    }

    public void allowAlways(String toolName) {
        alwaysAllowed.add(toolName);
    }

    public void reset() {
        alwaysAllowed.clear();
    }
}
