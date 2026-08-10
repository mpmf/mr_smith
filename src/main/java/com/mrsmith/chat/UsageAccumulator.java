package com.mrsmith.chat;

import com.mrsmith.provider.Usage;

public final class UsageAccumulator {

    private int promptTokens;
    private int completionTokens;
    private boolean estimated;

    public void add(Usage usage, boolean estimated) {
        if (usage == null) {
            return;
        }
        if (estimated) {
            this.estimated = true;
        }
        if (usage.promptTokens() != null) {
            promptTokens += usage.promptTokens();
        }
        if (usage.completionTokens() != null) {
            completionTokens += usage.completionTokens();
        }
    }

    public int promptTokens() {
        return promptTokens;
    }

    public int completionTokens() {
        return completionTokens;
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public boolean estimated() {
        return estimated;
    }

    public Usage snapshot() {
        return new Usage(promptTokens, completionTokens);
    }

    public void reset() {
        promptTokens = 0;
        completionTokens = 0;
        estimated = false;
    }
}
