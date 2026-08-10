package com.mrsmith.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class JsonTest {

    @Test
    void providesSharedMapper() {
        assertNotNull(Json.MAPPER);
    }
}
