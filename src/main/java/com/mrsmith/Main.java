package com.mrsmith;

import com.mrsmith.cli.ChatCommand;
import picocli.CommandLine;

public class Main {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ChatCommand()).execute(args);
        System.exit(exitCode);
    }
}
