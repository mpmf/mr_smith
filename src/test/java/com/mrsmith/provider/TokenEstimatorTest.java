package com.mrsmith.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenEstimatorTest {

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
}
