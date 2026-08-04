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
import java.util.UUID;

public class FileTranscriptWriter implements TranscriptWriter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path sessionsRoot;

    public FileTranscriptWriter(Path sessionsRoot) {
        this.sessionsRoot = sessionsRoot;
    }

    @Override
    public void start(UUID sessionId) throws IOException {
        Files.createDirectories(folder(sessionId));
    }

    @Override
    public void appendUser(UUID sessionId, String content) throws IOException {
        ObjectNode record = JSON.createObjectNode();
        record.put("type", "user");
        record.put("content", content);
        record.put("timestamp", Instant.now().toString());
        append(sessionId, record);
    }

    @Override
    public void appendAssistant(UUID sessionId, String content, String thinking,
                                Usage usage, boolean estimated) throws IOException {
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
        append(sessionId, record);
    }

    @Override
    public void appendToolCall(UUID sessionId, String id, String name, JsonNode arguments) throws IOException {
        ObjectNode record = JSON.createObjectNode();
        record.put("type", "tool_call");
        record.put("id", id);
        record.put("name", name);
        if (arguments != null) {
            record.set("arguments", arguments);
        }
        record.put("timestamp", Instant.now().toString());
        append(sessionId, record);
    }

    @Override
    public void appendToolResult(UUID sessionId, String id, String content, boolean error) throws IOException {
        ObjectNode record = JSON.createObjectNode();
        record.put("type", "tool_result");
        record.put("id", id);
        record.put("content", content);
        record.put("error", error);
        record.put("timestamp", Instant.now().toString());
        append(sessionId, record);
    }

    private void append(UUID sessionId, ObjectNode record) throws IOException {
        String line = JSON.writeValueAsString(record);
        Files.writeString(transcriptFile(sessionId), line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private Path folder(UUID sessionId) {
        return sessionsRoot.resolve(sessionId.toString());
    }

    private Path transcriptFile(UUID sessionId) {
        return folder(sessionId).resolve("transcript.jsonl");
    }
}
