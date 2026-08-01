package com.mrsmith.chat;

import com.mrsmith.provider.Usage;

import java.util.Locale;

public class UsageTracker {

    private int promptTokens;
    private int completionTokens;
    private boolean sessionEstimated;
    private Usage lastTurn;
    private boolean lastTurnEstimated;

    public void recordTurn(Usage usage, boolean estimated) {
        if (usage == null) {
            return;
        }
        lastTurn = usage;
        lastTurnEstimated = estimated;
        if (estimated) {
            sessionEstimated = true;
        }
        if (usage.promptTokens() != null) {
            promptTokens += usage.promptTokens();
        }
        if (usage.completionTokens() != null) {
            completionTokens += usage.completionTokens();
        }
    }

    public String lastTurnLine() {
        if (lastTurn == null) {
            return "";
        }
        int in = lastTurn.promptTokens() == null ? 0 : lastTurn.promptTokens();
        int out = lastTurn.completionTokens() == null ? 0 : lastTurn.completionTokens();
        String turnEst = lastTurnEstimated ? " (est.)" : "";
        String sessionEst = sessionEstimated ? " (est.)" : "";
        return String.format(Locale.US,
                "tokens: %,d in%s · %,d out%s · total %,d · session %,d%s",
                in, turnEst, out, turnEst, in + out, totalTokens(), sessionEst);
    }

    public String usageReport() {
        String est = sessionEstimated ? " (est.)" : "";
        return String.format(Locale.US,
                "Session usage:%n  prompt:      %,d%n  completion:  %,d%n  total:       %,d%s",
                promptTokens, completionTokens, totalTokens(), est);
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

    public boolean sessionEstimated() {
        return sessionEstimated;
    }

    public void reset() {
        promptTokens = 0;
        completionTokens = 0;
        sessionEstimated = false;
        lastTurn = null;
        lastTurnEstimated = false;
    }
}
