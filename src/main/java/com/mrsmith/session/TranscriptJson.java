package com.mrsmith.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.provider.Usage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

final class TranscriptJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TranscriptJson() {
    }

    static void append(Path file, ObjectNode record) throws IOException {
        String line = JSON.writeValueAsString(record);
        Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static ObjectNode user(String content) {
        ObjectNode record = JSON.createObjectNode();
        record.put("type", "user");
        record.put("content", content);
        record.put("timestamp", Instant.now().toString());
        return record;
    }

    static ObjectNode assistant(String content, String thinking, Usage usage, boolean estimated) {
        ObjectNode record = JSON.createObjectNode();
        record.put("type", "assistant");
        record.put("content", content);
        if (thinking != null) {
            record.put("thinking", thinking);
        }
        if (usage != null) {
            if (usage.promptTokens() != null) {
                record.put("promptTokens", usage.promptTokens());
            }
            if (usage.completionTokens() != null) {
                record.put("completionTokens", usage.completionTokens());
            }
        }
        record.put("estimated", estimated);
        record.put("timestamp", Instant.now().toString());
        return record;
    }

    static ObjectNode toolCall(String id, String name, JsonNode arguments) {
        ObjectNode record = JSON.createObjectNode();
        record.put("type", "tool_call");
        record.put("id", id);
        record.put("name", name);
        if (arguments != null) {
            record.set("arguments", arguments);
        }
        record.put("timestamp", Instant.now().toString());
        return record;
    }

    static ObjectNode toolResult(String id, String content, boolean error) {
        ObjectNode record = JSON.createObjectNode();
        record.put("type", "tool_result");
        record.put("id", id);
        record.put("content", content);
        record.put("error", error);
        record.put("timestamp", Instant.now().toString());
        return record;
    }

    static ObjectNode skillLoad(String name) {
        ObjectNode record = JSON.createObjectNode();
        record.put("type", "skill_load");
        record.put("name", name);
        record.put("timestamp", Instant.now().toString());
        return record;
    }
}
