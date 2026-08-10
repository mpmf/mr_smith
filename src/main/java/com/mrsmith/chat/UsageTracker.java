package com.mrsmith.chat;

import com.mrsmith.provider.Usage;

import java.util.Locale;

public class UsageTracker {

    private final UsageAccumulator accumulator = new UsageAccumulator();
    private Usage lastTurn;
    private boolean lastTurnEstimated;

    public void recordTurn(Usage usage, boolean estimated) {
        if (usage == null) {
            return;
        }
        lastTurn = usage;
        lastTurnEstimated = estimated;
        accumulator.add(usage, estimated);
    }

    public void recordSessionUsage(Usage usage, boolean estimated) {
        if (usage == null) {
            return;
        }
        accumulator.add(usage, estimated);
    }

    public String lastTurnLine() {
        if (lastTurn == null) {
            return "";
        }
        int in = lastTurn.promptTokens() == null ? 0 : lastTurn.promptTokens();
        int out = lastTurn.completionTokens() == null ? 0 : lastTurn.completionTokens();
        String turnEst = lastTurnEstimated ? " (est.)" : "";
        String sessionEst = accumulator.estimated() ? " (est.)" : "";
        return String.format(Locale.US,
                "tokens: %,d in%s · %,d out%s · total %,d · session %,d%s",
                in, turnEst, out, turnEst, in + out, totalTokens(), sessionEst);
    }

    public String usageReport() {
        String est = accumulator.estimated() ? " (est.)" : "";
        return String.format(Locale.US,
                "Session usage:%n  prompt:      %,d%n  completion:  %,d%n  total:       %,d%s",
                promptTokens(), completionTokens(), totalTokens(), est);
    }

    public int promptTokens() {
        return accumulator.promptTokens();
    }

    public int completionTokens() {
        return accumulator.completionTokens();
    }

    public int totalTokens() {
        return accumulator.totalTokens();
    }

    public boolean sessionEstimated() {
        return accumulator.estimated();
    }

    public void reset() {
        accumulator.reset();
        lastTurn = null;
        lastTurnEstimated = false;
    }
}
