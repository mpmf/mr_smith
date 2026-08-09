package com.mrsmith.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.mrsmith.provider.Usage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class SubAgentTranscriptWriter implements TranscriptWriter {

    private final Path file;

    public SubAgentTranscriptWriter(Path file) {
        this.file = file;
    }

    @Override
    public void start(UUID sessionId) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    @Override
    public void appendUser(UUID sessionId, String content) throws IOException {
        TranscriptJson.append(file, TranscriptJson.user(content));
    }

    @Override
    public void appendAssistant(UUID sessionId, String content, String thinking,
                                Usage usage, boolean estimated) throws IOException {
        TranscriptJson.append(file, TranscriptJson.assistant(content, thinking, usage, estimated));
    }

    @Override
    public void appendToolCall(UUID sessionId, String id, String name, JsonNode arguments) throws IOException {
        TranscriptJson.append(file, TranscriptJson.toolCall(id, name, arguments));
    }

    @Override
    public void appendToolResult(UUID sessionId, String id, String content, boolean error) throws IOException {
        TranscriptJson.append(file, TranscriptJson.toolResult(id, content, error));
    }

    @Override
    public void appendSkillLoad(UUID sessionId, String name) throws IOException {
        TranscriptJson.append(file, TranscriptJson.skillLoad(name));
    }
}
