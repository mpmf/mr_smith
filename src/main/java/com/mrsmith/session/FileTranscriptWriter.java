package com.mrsmith.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.mrsmith.provider.Usage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class FileTranscriptWriter implements TranscriptWriter {

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
        TranscriptJson.append(transcriptFile(sessionId), TranscriptJson.user(content));
    }

    @Override
    public void appendAssistant(UUID sessionId, String content, String thinking,
                                Usage usage, boolean estimated) throws IOException {
        TranscriptJson.append(transcriptFile(sessionId),
                TranscriptJson.assistant(content, thinking, usage, estimated));
    }

    @Override
    public void appendToolCall(UUID sessionId, String id, String name, JsonNode arguments) throws IOException {
        TranscriptJson.append(transcriptFile(sessionId), TranscriptJson.toolCall(id, name, arguments));
    }

    @Override
    public void appendToolResult(UUID sessionId, String id, String content, boolean error) throws IOException {
        TranscriptJson.append(transcriptFile(sessionId), TranscriptJson.toolResult(id, content, error));
    }

    @Override
    public void appendSkillLoad(UUID sessionId, String name) throws IOException {
        TranscriptJson.append(transcriptFile(sessionId), TranscriptJson.skillLoad(name));
    }

    private Path folder(UUID sessionId) {
        return sessionsRoot.resolve(sessionId.toString());
    }

    private Path transcriptFile(UUID sessionId) {
        return folder(sessionId).resolve("transcript.jsonl");
    }
}
