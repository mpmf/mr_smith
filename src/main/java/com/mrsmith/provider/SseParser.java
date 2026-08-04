package com.mrsmith.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
        Map<Integer, ToolCallAccumulator> toolCalls = new TreeMap<>();
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
                accumulateToolCalls(delta, toolCalls);
            }
        }
        return new SseResult(content.toString(), thinking.isEmpty() ? null : thinking.toString(),
                buildToolCalls(toolCalls), usage);
    }

    private static void accumulateToolCalls(JsonNode delta, Map<Integer, ToolCallAccumulator> toolCalls) {
        JsonNode deltas = delta.path("tool_calls");
        if (!deltas.isArray()) {
            return;
        }
        for (JsonNode tc : deltas) {
            int index = tc.path("index").asInt();
            ToolCallAccumulator acc = toolCalls.computeIfAbsent(index, i -> new ToolCallAccumulator());
            if (tc.hasNonNull("id")) {
                acc.id = tc.get("id").asText();
            }
            JsonNode fn = tc.path("function");
            if (fn.isObject()) {
                if (fn.hasNonNull("name")) {
                    acc.name = fn.get("name").asText();
                }
                if (fn.hasNonNull("arguments")) {
                    acc.arguments.append(fn.get("arguments").asText());
                }
            }
        }
    }

    private static List<ToolCall> buildToolCalls(Map<Integer, ToolCallAccumulator> toolCalls) {
        if (toolCalls.isEmpty()) {
            return null;
        }
        List<ToolCall> calls = new ArrayList<>();
        for (ToolCallAccumulator acc : toolCalls.values()) {
            calls.add(new ToolCall(acc.id, acc.name, parseArguments(acc.arguments.toString())));
        }
        return calls;
    }

    private static JsonNode parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(arguments);
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
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

    private static final class ToolCallAccumulator {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
