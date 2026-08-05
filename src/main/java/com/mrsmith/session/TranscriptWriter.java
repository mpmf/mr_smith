package com.mrsmith.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.mrsmith.provider.Usage;

import java.io.IOException;
import java.util.UUID;

public interface TranscriptWriter {

    void start(UUID sessionId) throws IOException;

    void appendUser(UUID sessionId, String content) throws IOException;

    void appendAssistant(UUID sessionId, String content, String thinking,
                         Usage usage, boolean estimated) throws IOException;

    void appendToolCall(UUID sessionId, String id, String name, JsonNode arguments) throws IOException;

    void appendToolResult(UUID sessionId, String id, String content, boolean error) throws IOException;

    void appendSkillLoad(UUID sessionId, String name) throws IOException;
}
