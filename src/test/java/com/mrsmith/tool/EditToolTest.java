package com.mrsmith.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private EditTool tool() {
        return new EditTool(tempDir);
    }

    @Test
    void replacesSingleMatch() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "hello world");
        ToolResult result = tool().execute(JSON.readTree(
                "{\"filePath\":\"a.txt\",\"oldString\":\"world\",\"newString\":\"there\"}"));
        assertFalse(result.error());
        assertEquals("Edited a.txt (1 replacements)", result.content());
        assertEquals("hello there", Files.readString(file));
    }

    @Test
    void noMatchIsErrorAndFileUnchanged() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "hello world");
        ToolResult result = tool().execute(JSON.readTree(
                "{\"filePath\":\"a.txt\",\"oldString\":\"xyz\",\"newString\":\"abc\"}"));
        assertTrue(result.error());
        assertTrue(result.content().contains("oldString not found"));
        assertEquals("hello world", Files.readString(file));
    }

    @Test
    void multipleMatchesWithoutReplaceAllIsError() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "foo foo");
        ToolResult result = tool().execute(JSON.readTree(
                "{\"filePath\":\"a.txt\",\"oldString\":\"foo\",\"newString\":\"bar\"}"));
        assertTrue(result.error());
        assertTrue(result.content().contains("found 2 matches"));
        assertEquals("foo foo", Files.readString(file));
    }

    @Test
    void replaceAllReplacesEveryOccurrence() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "foo foo");
        ToolResult result = tool().execute(JSON.readTree(
                "{\"filePath\":\"a.txt\",\"oldString\":\"foo\",\"newString\":\"bar\",\"replaceAll\":true}"));
        assertFalse(result.error());
        assertEquals("Edited a.txt (2 replacements)", result.content());
        assertEquals("bar bar", Files.readString(file));
    }

    @Test
    void newStringEqualToOldStringIsError() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "foo");
        ToolResult result = tool().execute(JSON.readTree(
                "{\"filePath\":\"a.txt\",\"oldString\":\"foo\",\"newString\":\"foo\"}"));
        assertTrue(result.error());
        assertEquals("newString must differ from oldString", result.content());
        assertEquals("foo", Files.readString(file));
    }

    @Test
    void missingFileIsError() throws Exception {
        ToolResult result = tool().execute(JSON.readTree(
                "{\"filePath\":\"nope.txt\",\"oldString\":\"x\",\"newString\":\"y\"}"));
        assertTrue(result.error());
        assertTrue(result.content().contains("file not found"));
    }

    @Test
    void pathEscapingWorkingDirectoryIsError() throws Exception {
        ToolResult result = tool().execute(JSON.readTree(
                "{\"filePath\":\"../escape.txt\",\"oldString\":\"x\",\"newString\":\"y\"}"));
        assertTrue(result.error());
        assertTrue(result.content().contains("escapes"));
    }

    @Test
    void missingArgumentsThrow() {
        assertThrows(ToolException.class, () -> tool().execute(JSON.readTree("{}")));
    }
}
