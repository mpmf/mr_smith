package com.mrsmith.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenEstimatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void emptyOrNullIsZero() {
        assertEquals(0, TokenEstimator.estimateTokens(""));
        assertEquals(0, TokenEstimator.estimateTokens(null));
    }

    @Test
    void fourCharsAreOneToken() {
        assertEquals(1, TokenEstimator.estimateTokens("abcd"));
    }

    @Test
    void roundsUpPartialToken() {
        assertEquals(2, TokenEstimator.estimateTokens("abcde"));
    }

    @Test
    void messageEstimateEqualsContentEstimateForPlainMessage() {
        assertEquals(1, TokenEstimator.estimateMessageTokens(new ChatMessage(Role.USER, "abcd")));
        assertEquals(0, TokenEstimator.estimateMessageTokens(new ChatMessage(Role.USER, null)));
    }

    @Test
    void messageEstimateCountsToolCallParts() throws Exception {
        ChatMessage msg = new ChatMessage(Role.ASSISTANT, null, null,
                List.of(new ToolCall("c1", "t", JSON.readTree("{}"))), null);
        assertEquals(3, TokenEstimator.estimateMessageTokens(msg));
    }

    @Test
    void messageEstimateCountsToolResultIdAndContent() {
        ChatMessage msg = new ChatMessage(Role.TOOL, "r", null, null, "c1");
        assertEquals(2, TokenEstimator.estimateMessageTokens(msg));
    }
}
