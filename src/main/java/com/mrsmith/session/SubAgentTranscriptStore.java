package com.mrsmith.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Role;
import com.mrsmith.provider.ToolCall;
import com.mrsmith.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public final class SubAgentTranscriptStore {

    private static final ObjectMapper JSON = Json.MAPPER;

    private final Path sessionsRoot;
    private final Supplier<UUID> sessionId;

    public SubAgentTranscriptStore(Path sessionsRoot, Supplier<UUID> sessionId) {
        this.sessionsRoot = sessionsRoot;
        this.sessionId = sessionId;
    }

    public Path file(int n) {
        return sessionsRoot.resolve(sessionId.get().toString()).resolve("subagent-" + n + ".jsonl");
    }

    public TranscriptWriter writer(int n) {
        return new SubAgentTranscriptWriter(file(n));
    }

    public List<ChatMessage> read(int n) throws IOException {
        Path path = file(n);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        List<ChatMessage> messages = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            JsonNode record = JSON.readTree(line);
            switch (record.path("type").asText()) {
                case "user" -> messages.add(new ChatMessage(Role.USER, record.path("content").asText()));
                case "assistant" -> messages.add(new ChatMessage(Role.ASSISTANT,
                        record.path("content").asText(),
                        record.has("thinking") ? record.path("thinking").asText() : null));
                case "tool_call" -> messages.add(new ChatMessage(Role.ASSISTANT, null, null,
                        List.of(new ToolCall(record.path("id").asText(), record.path("name").asText(),
                                record.has("arguments") ? record.get("arguments") : null)), null));
                case "tool_result" -> messages.add(new ChatMessage(Role.TOOL,
                        record.path("content").asText(), null, null, record.path("id").asText()));
                default -> {
                    // skill_load and unknown records are skipped on replay
                }
            }
        }
        return messages;
    }
}
