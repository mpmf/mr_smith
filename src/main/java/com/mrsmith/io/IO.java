package com.mrsmith.io;

import java.io.IOException;

public interface IO {

    String readLine() throws IOException;

    void write(String text);

    void writeLine(String line);

    void writeReasoning(String text);

    void writeToolExecution(String text);

    void writePrompt(String text);
}
