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

    public static SseResult consume(BufferedReader reader, Consumer<String> contentSink,
                                    Consumer<String> reasoningSink) throws IOException {
        StringBuilder content = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        boolean reasoningStreamed = false;
        boolean transitionNewlineSent = false;
        Usage usage = null;
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
            JsonNode node = parse(payload);
            if (node == null) {
                continue;
            }
            Usage chunkUsage = extractUsage(node);
            if (chunkUsage != null) {
                usage = chunkUsage;
            }
            JsonNode delta = node.path("choices").path(0).path("delta");
            if (!delta.isMissingNode()) {
                String reasoning = extractReasoning(delta);
                if (reasoning != null && !reasoning.isEmpty()) {
                    reasoningSink.accept(reasoning);
                    thinking.append(reasoning);
                    reasoningStreamed = true;
                }
                String contentDelta = delta.path("content").asText(null);
                if (contentDelta != null && !contentDelta.isEmpty()) {
                    if (reasoningStreamed && !transitionNewlineSent) {
                        transitionNewlineSent = true;
                        contentSink.accept("\n");
                    }
                    contentSink.accept(contentDelta);
                    content.append(contentDelta);
                }
            }
        }
        return new SseResult(content.toString(), thinking.isEmpty() ? null : thinking.toString(), usage);
    }

    private static String extractReasoning(JsonNode delta) {
        String reasoning = delta.path("reasoning_content").asText(null);
        if (reasoning == null) {
            reasoning = delta.path("reasoning").asText(null);
        }
        return reasoning;
    }

    private static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (IOException e) {
            System.err.println("Warning: malformed SSE chunk, skipping: " + payload);
            return null;
        }
    }

    private static Usage extractUsage(JsonNode node) {
        JsonNode usageNode = node.path("usage");
        if (usageNode.isMissingNode() || usageNode.isNull()) {
            return null;
        }
        Integer prompt = usageNode.hasNonNull("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : null;
        Integer completion = usageNode.hasNonNull("completion_tokens") ? usageNode.get("completion_tokens").asInt() : null;
        if (prompt == null && completion == null) {
            return null;
        }
        return new Usage(prompt, completion);
    }
}
