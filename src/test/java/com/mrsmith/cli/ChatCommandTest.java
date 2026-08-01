package com.mrsmith.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCommandTest {

    @Test
    void helpExitsZeroAndPrintsUsage() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = new CommandLine(new ChatCommand()).execute("--help");
            assertEquals(0, exit);
            assertTrue(out.toString().contains("--model"));
        } finally {
            System.setOut(original);
        }
    }
}
