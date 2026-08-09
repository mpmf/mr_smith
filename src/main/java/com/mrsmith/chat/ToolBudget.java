package com.mrsmith.chat;

import com.mrsmith.io.IO;

import java.util.Locale;

/**
 * Session-scoped budget on executed tool calls, shared between the main tool
 * loop and sub-agents. A null / non-positive limit means unlimited.
 *
 * <p>The budget is created fresh on every session start ({@code /reset} and
 * agent switches), warns once when it crosses {@value #WARN_PERCENT}% of the
 * limit, and lets the tool loop hard-stop when exhausted.
 */
public final class ToolBudget {

    public static final int WARN_PERCENT = 80;

    private final int limit;
    private final IO io;
    private int used;
    private boolean warned;

    public ToolBudget(Integer limit, IO io) {
        this.limit = limit == null ? -1 : limit;
        this.io = io;
    }

    public boolean isUnlimited() {
        return limit <= 0;
    }

    public int limit() {
        return limit;
    }

    public int used() {
        return used;
    }

    /** True once the budget is exhausted and further tool calls must stop. */
    public boolean exhausted() {
        return !isUnlimited() && used >= limit;
    }

    /** Records one executed tool call; emits a one-time warning past the threshold. */
    public void record() {
        used++;
        if (!isUnlimited() && !warned && used * 100 >= limit * WARN_PERCENT) {
            warned = true;
            int pct = used * 100 / limit;
            io.writeLine(String.format(Locale.US,
                    "Warning: tool call budget %d%% used (%d/%d) — consider /reset",
                    pct, used, limit));
        }
    }
}
