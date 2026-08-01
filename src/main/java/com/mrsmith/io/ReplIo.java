package com.mrsmith.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;

public class ReplIo implements IO {

    private final BufferedReader reader;
    private final PrintStream out;

    public ReplIo() {
        this(new BufferedReader(new InputStreamReader(System.in)), System.out);
    }

    ReplIo(BufferedReader reader, PrintStream out) {
        this.reader = reader;
        this.out = out;
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
}
