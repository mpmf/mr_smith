package com.mrsmith.provider;

public final class TokenEstimator {

    private static final int CHARS_PER_TOKEN = 4;

    private TokenEstimator() {
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / (double) CHARS_PER_TOKEN);
    }
}
