package com.mrsmith.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {

    @Test
    void fourArgConstructorDefaultsIncludeUsageTrueAndMaxContextNull() {
        AppConfig config = new AppConfig("sk", "https://example.com/v1", "gpt", null);
        assertTrue(config.includeUsage());
        assertNull(config.maxContextTokens());
    }

    @Test
    void fullConstructorPreservesValues() {
        AppConfig config = new AppConfig("sk", "https://example.com/v1/", "gpt", "sys", 8192, false);
        assertEquals(8192, config.maxContextTokens());
        assertEquals(false, config.includeUsage());
        assertEquals("https://example.com/v1", config.baseUrl());
    }
}
