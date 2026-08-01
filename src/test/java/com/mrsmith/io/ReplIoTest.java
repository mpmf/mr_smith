package com.mrsmith.io;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplIoTest {

    private static final String YELLOW = "\u001B[33m";
    private static final String RESET = "\u001B[0m";

    @Test
    void readsLinesFromReader() throws IOException {
        ReplIo io = new ReplIo(new BufferedReader(new StringReader("hello\n/exit\n")), new PrintStream(new ByteArrayOutputStream()), false);
        assertEquals("hello", io.readLine());
        assertEquals("/exit", io.readLine());
    }

    @Test
    void writeAppendsWithoutNewline() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ReplIo io = new ReplIo(new BufferedReader(new StringReader("")), new PrintStream(new BufferedOutputStream(buffer)), false);
        io.write("a");
        io.write("b");
        assertEquals("ab", buffer.toString());
    }

    @Test
    void writeLineAppendsNewline() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ReplIo io = new ReplIo(new BufferedReader(new StringReader("")), new PrintStream(buffer), false);
        io.writeLine("hi");
        assertEquals("hi" + System.lineSeparator(), buffer.toString());
    }

    @Test
    void writeReasoningWrapsInYellowWhenColorEnabled() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ReplIo io = new ReplIo(new BufferedReader(new StringReader("")), new PrintStream(buffer), true);
        io.writeReasoning("think");
        assertEquals(YELLOW + "think" + RESET, buffer.toString());
    }

    @Test
    void writeReasoningIsPlainWhenColorDisabled() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ReplIo io = new ReplIo(new BufferedReader(new StringReader("")), new PrintStream(buffer), false);
        io.writeReasoning("think");
        assertEquals("think", buffer.toString());
    }
}
