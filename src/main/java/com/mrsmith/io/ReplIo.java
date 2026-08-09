package com.mrsmith.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;

public class ReplIo implements IO {

    private static final String YELLOW = "\u001B[33m";
    private static final String LIGHT_BLUE = "\u001B[94m";
    private static final String LIGHT_GREEN = "\u001B[92m";
    private static final String RESET = "\u001B[0m";

    private final BufferedReader reader;
    private final PrintStream out;
    private final boolean colorEnabled;

    public ReplIo() {
        this(new BufferedReader(new InputStreamReader(System.in)), System.out,
                System.console() != null);
    }

    ReplIo(BufferedReader reader, PrintStream out, boolean colorEnabled) {
        this.reader = reader;
        this.out = out;
        this.colorEnabled = colorEnabled;
    }

    @Override
    public String readLine() throws IOException {
        return reader.readLine();
    }

    @Override
    public void write(String text) {
        out.print(text);
        out.flush();
    }

    @Override
    public void writeLine(String line) {
        out.println(line);
    }

    @Override
    public void writeReasoning(String text) {
        if (colorEnabled) {
            out.print(YELLOW);
            out.print(text);
            out.print(RESET);
        } else {
            out.print(text);
        }
        out.flush();
    }

    @Override
    public void writeToolExecution(String text) {
        if (colorEnabled) {
            out.print(LIGHT_BLUE);
            out.println("");
            out.print(text);
            out.println("");
            out.print(RESET);
        } else {
            out.println("");
            out.print(text);
            out.println("");
        }
        out.flush();
    }

    @Override
    public void writePrompt(String text) {
        out.println("");
        if (colorEnabled) {
            out.print(LIGHT_GREEN);
            out.print(text);
            out.print(RESET);
        } else {
            out.print(text);
        }
        out.println("");
    }
}
