package com.mrsmith.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningEffortTest {

    @Test
    void defaultsToUnset() {
        ReasoningEffort effort = new ReasoningEffort();
        assertFalse(effort.isSet());
        assertNull(effort.override());
    }

    @Test
    void setThenOverride() {
        ReasoningEffort effort = new ReasoningEffort();
        effort.set("high");
        assertTrue(effort.isSet());
        assertEquals("high", effort.override());
    }

    @Test
    void clearResetsToUnset() {
        ReasoningEffort effort = new ReasoningEffort();
        effort.set("high");
        effort.clear();
        assertFalse(effort.isSet());
        assertNull(effort.override());
    }

    @Test
    void effectiveReturnsOverrideWhenSet() {
        ReasoningEffort effort = new ReasoningEffort();
        effort.set("high");
        assertEquals("high", effort.effective("low"));
    }

    @Test
    void effectiveReturnsConfiguredWhenNotSet() {
        ReasoningEffort effort = new ReasoningEffort();
        assertEquals("low", effort.effective("low"));
    }

    @Test
    void effectiveReturnsConfiguredWhenOverrideBlank() {
        ReasoningEffort effort = new ReasoningEffort();
        effort.set("   ");
        assertFalse(effort.isSet());
        assertEquals("low", effort.effective("low"));
    }
}
