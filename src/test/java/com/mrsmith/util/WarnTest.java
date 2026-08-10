package com.mrsmith.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WarnTest {

    @Test
    void warnsWithPrefix() {
        PrintStream original = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            Warn.warn("boom");
        } finally {
            System.setErr(original);
        }
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Warning: boom"));
    }
}
