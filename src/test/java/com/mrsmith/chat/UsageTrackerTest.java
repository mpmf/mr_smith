package com.mrsmith.chat;

import com.mrsmith.provider.Usage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageTrackerTest {

    @Test
    void accumulatesTurns() {
        UsageTracker tracker = new UsageTracker();
        tracker.recordTurn(new Usage(1200, 300), false);
        tracker.recordTurn(new Usage(800, 200), false);
        assertEquals(2000, tracker.promptTokens());
        assertEquals(500, tracker.completionTokens());
        assertEquals(2500, tracker.totalTokens());
        assertFalse(tracker.sessionEstimated());
    }

    @Test
    void lastTurnLineFormatsWithGrouping() {
        UsageTracker tracker = new UsageTracker();
        tracker.recordTurn(new Usage(1234, 345), false);
        assertEquals("tokens: 1,234 in · 345 out · total 1,579 · session 1,579",
                tracker.lastTurnLine());
    }

    @Test
    void estimatedTurnsAreFlagged() {
        UsageTracker tracker = new UsageTracker();
        tracker.recordTurn(new Usage(100, 50), true);
        assertEquals("tokens: 100 in (est.) · 50 out (est.) · total 150 · session 150 (est.)",
                tracker.lastTurnLine());
        assertTrue(tracker.sessionEstimated());
    }

    @Test
    void sessionEstimatedStaysTrueAfterRealTurn() {
        UsageTracker tracker = new UsageTracker();
        tracker.recordTurn(new Usage(100, 50), true);
        tracker.recordTurn(new Usage(100, 50), false);
        assertEquals("tokens: 100 in · 50 out · total 150 · session 300 (est.)",
                tracker.lastTurnLine());
    }

    @Test
    void usageReportListsTotals() {
        UsageTracker tracker = new UsageTracker();
        tracker.recordTurn(new Usage(12000, 3456), false);
        assertEquals("Session usage:\n  prompt:      12,000\n  completion:  3,456\n  total:       15,456",
                tracker.usageReport());
    }

    @Test
    void usageReportFlagsEstimatedSession() {
        UsageTracker tracker = new UsageTracker();
        tracker.recordTurn(new Usage(100, 50), true);
        assertEquals("Session usage:\n  prompt:      100\n  completion:  50\n  total:       150 (est.)",
                tracker.usageReport());
    }

    @Test
    void nullUsageIsIgnored() {
        UsageTracker tracker = new UsageTracker();
        tracker.recordTurn(null, false);
        assertEquals(0, tracker.totalTokens());
        assertEquals("", tracker.lastTurnLine());
    }

    @Test
    void resetClearsTotals() {
        UsageTracker tracker = new UsageTracker();
        tracker.recordTurn(new Usage(100, 50), true);
        tracker.reset();
        assertEquals(0, tracker.totalTokens());
        assertFalse(tracker.sessionEstimated());
        assertEquals("", tracker.lastTurnLine());
    }
}
