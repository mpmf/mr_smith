package com.mrsmith.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Role;
import com.mrsmith.provider.ToolCall;
import com.mrsmith.provider.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubAgentTranscriptStoreTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private final UUID sessionId = UUID.randomUUID();

    private SubAgentTranscriptStore store() {
        return new SubAgentTranscriptStore(tempDir, () -> sessionId);
    }

    @Test
    void writesAndReadsRecordsRoundTrip() throws IOException {
        Files.createDirectories(tempDir.resolve(sessionId.toString()));
        SubAgentTranscriptStore store = store();
        TranscriptWriter writer = store.writer(1);
        writer.start(sessionId);
        writer.appendUser(sessionId, "do the thing");
        writer.appendAssistant(sessionId, "answer", "ponder", new Usage(100, 50), false);
        writer.appendToolCall(sessionId, "c1", "read_file", JSON.readTree("{\"path\":\"a.txt\"}"));
        writer.appendToolResult(sessionId, "c1", "file contents", false);

        Path file = tempDir.resolve(sessionId.toString()).resolve("subagent-1.jsonl");
        assertTrue(Files.isRegularFile(file));
        List<String> lines = Files.readAllLines(file);
        assertEquals(4, lines.size());

        List<ChatMessage> messages = store.read(1);
        assertEquals(4, messages.size());
        assertEquals(Role.USER, messages.get(0).role());
        assertEquals("do the thing", messages.get(0).content());
        assertEquals(Role.ASSISTANT, messages.get(1).role());
        assertEquals("answer", messages.get(1).content());
        assertEquals(Role.ASSISTANT, messages.get(2).role());
        assertEquals(1, messages.get(2).toolCalls().size());
        assertEquals("c1", messages.get(2).toolCalls().get(0).id());
        assertEquals(Role.TOOL, messages.get(3).role());
        assertEquals("c1", messages.get(3).toolCallId());
        assertEquals("file contents", messages.get(3).content());
    }

    @Test
    void missingFileReadsAsNull() throws IOException {
        assertNull(store().read(7));
    }

    @Test
    void fileNamesAreSequentialPerSession() {
        assertEquals("subagent-1.jsonl", store().file(1).getFileName().toString());
        assertEquals("subagent-2.jsonl", store().file(2).getFileName().toString());
    }
}
