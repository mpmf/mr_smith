package com.mrsmith.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.function.Consumer;

public final class SseParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SseParser() {
    }

    public static String consume(BufferedReader reader, Consumer<String> deltaSink) throws IOException {
        StringBuilder full = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring("data:".length()).trim();
            if (payload.equals("[DONE]")) {
                break;
            }
            if (payload.isEmpty()) {
                continue;
            }
            String delta = extractDelta(payload);
            if (delta != null && !delta.isEmpty()) {
                deltaSink.accept(delta);
                full.append(delta);
            }
        }
        return full.toString();
    }

    private static String extractDelta(String payload) {
        try {
            JsonNode node = JSON.readTree(payload);
            JsonNode delta = node.path("choices").path(0).path("delta");
            return delta.isMissingNode() ? null : delta.path("content").asText(null);
        } catch (IOException e) {
            System.err.println("Warning: malformed SSE chunk, skipping: " + payload);
            return null;
        }
    }
}
