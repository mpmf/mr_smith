package com.mrsmith.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrsmith.provider.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTranscriptWriterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void startCreatesSessionFolder() throws IOException {
        FileTranscriptWriter writer = new FileTranscriptWriter(tempDir);
        UUID id = UUID.randomUUID();
        writer.start(id);
        assertTrue(Files.isDirectory(tempDir.resolve(id.toString())));
    }

    @Test
    void appendsUserAndAssistantRecords() throws IOException {
        FileTranscriptWriter writer = new FileTranscriptWriter(tempDir);
        UUID id = UUID.randomUUID();
        writer.start(id);
        writer.appendUser(id, "hello");
        writer.appendAssistant(id, "hi there", "ponder", new Usage(1200, 300), false);

        Path file = tempDir.resolve(id.toString()).resolve("transcript.jsonl");
        assertTrue(Files.exists(file));
        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());

        JsonNode user = JSON.readTree(lines.get(0));
        assertEquals("user", user.get("type").asText());
        assertEquals("hello", user.get("content").asText());
        assertTrue(user.hasNonNull("timestamp"));

        JsonNode assistant = JSON.readTree(lines.get(1));
        assertEquals("assistant", assistant.get("type").asText());
        assertEquals("hi there", assistant.get("content").asText());
        assertEquals("ponder", assistant.get("thinking").asText());
        assertEquals(1200, assistant.get("promptTokens").asInt());
        assertEquals(300, assistant.get("completionTokens").asInt());
        assertFalse(assistant.get("estimated").asBoolean());
        assertTrue(assistant.hasNonNull("timestamp"));
    }

    @Test
    void omitsThinkingAndUsageWhenNull() throws IOException {
        FileTranscriptWriter writer = new FileTranscriptWriter(tempDir);
        UUID id = UUID.randomUUID();
        writer.start(id);
        writer.appendAssistant(id, "answer", null, null, false);
        JsonNode record = JSON.readTree(
                Files.readString(tempDir.resolve(id.toString()).resolve("transcript.jsonl")));
        assertTrue(record.get("thinking") == null);
        assertTrue(record.get("promptTokens") == null);
    }

    @Test
    void appendsAccumulateAsLines() throws IOException {
        FileTranscriptWriter writer = new FileTranscriptWriter(tempDir);
        UUID id = UUID.randomUUID();
        writer.start(id);
        writer.appendUser(id, "one");
        writer.appendUser(id, "two");
        Path file = tempDir.resolve(id.toString()).resolve("transcript.jsonl");
        assertEquals(2, Files.readAllLines(file).size());
    }
}
