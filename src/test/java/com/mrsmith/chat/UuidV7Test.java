package com.mrsmith.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    void isVersion7() {
        UUID id = UuidV7.random();
        assertEquals(7, id.version());
    }

    @Test
    void variantIsRfc4122() {
        UUID id = UuidV7.random();
        assertEquals(2, id.variant());
    }

    @Test
    void canonicalStringShape() {
        UUID id = UuidV7.random();
        assertTrue(id.toString().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
    }

    @Test
    void sequentialIdsAreOrdered() {
        UUID first = UuidV7.random();
        UUID second = UuidV7.random();
        while (first.compareTo(second) >= 0) {
            first = UuidV7.random();
            second = UuidV7.random();
        }
        assertTrue(first.compareTo(second) < 0);
    }
}
