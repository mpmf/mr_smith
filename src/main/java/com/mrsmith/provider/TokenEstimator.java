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

    public static int estimateMessageTokens(ChatMessage message) {
        int tokens = estimateTokens(message.content());
        if (message.toolCalls() != null) {
            for (ToolCall call : message.toolCalls()) {
                tokens += estimateTokens(call.id());
                tokens += estimateTokens(call.name());
                tokens += call.arguments() == null ? 0 : estimateTokens(call.arguments().toString());
            }
        }
        if (message.toolCallId() != null) {
            tokens += estimateTokens(message.toolCallId());
        }
        return tokens;
    }
}
