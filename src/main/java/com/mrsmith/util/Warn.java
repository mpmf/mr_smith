package com.mrsmith.util;

public final class Warn {

    private Warn() {
    }

    public static void warn(String message) {
        System.err.println("Warning: " + message);
    }
}
