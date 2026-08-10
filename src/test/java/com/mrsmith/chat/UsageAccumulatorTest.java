package com.mrsmith.chat;

import com.mrsmith.provider.Usage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageAccumulatorTest {

    @Test
    void accumulatesFields() {
        UsageAccumulator acc = new UsageAccumulator();
        acc.add(new Usage(1200, 300), false);
        acc.add(new Usage(800, 200), false);
        assertEquals(2000, acc.promptTokens());
        assertEquals(500, acc.completionTokens());
        assertEquals(2500, acc.totalTokens());
        assertFalse(acc.estimated());
    }

    @Test
    void skipsNullUsageAndFields() {
        UsageAccumulator acc = new UsageAccumulator();
        acc.add(null, false);
        acc.add(new Usage(1200, null), false);
        assertEquals(1200, acc.promptTokens());
        assertEquals(0, acc.completionTokens());
    }

    @Test
    void flagsEstimatedOnce() {
        UsageAccumulator acc = new UsageAccumulator();
        acc.add(new Usage(100, 50), true);
        acc.add(new Usage(100, 50), false);
        assertTrue(acc.estimated());
    }

    @Test
    void snapshotReturnsCurrentTotals() {
        UsageAccumulator acc = new UsageAccumulator();
        acc.add(new Usage(1200, 300), true);
        assertEquals(new Usage(1200, 300), acc.snapshot());
        assertTrue(acc.estimated());
    }

    @Test
    void resetClears() {
        UsageAccumulator acc = new UsageAccumulator();
        acc.add(new Usage(100, 50), true);
        acc.reset();
        assertEquals(0, acc.totalTokens());
        assertFalse(acc.estimated());
    }
}
