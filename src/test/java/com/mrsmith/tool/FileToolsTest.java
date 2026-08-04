package com.mrsmith.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void readFileReturnsContents() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Files.writeString(root.resolve("a.txt"), "hello");
        ReadFileTool tool = new ReadFileTool(root);
        ToolResult result = tool.execute(JSON.readTree("{\"path\":\"a.txt\"}"));
        assertFalse(result.error());
        assertEquals("hello", result.content());
    }

    @Test
    void readFileRejectsEscapeOutsideRoot() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "secret");
        ReadFileTool tool = new ReadFileTool(root);
        ToolResult result = tool.execute(JSON.readTree("{\"path\":\"../outside.txt\"}"));
        assertTrue(result.error());
        assertTrue(result.content().contains("escapes"));
    }

    @Test
    void writeFileCreatesParentsAndWrites() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        WriteFileTool tool = new WriteFileTool(root);
        ToolResult result = tool.execute(JSON.readTree("{\"path\":\"sub/deep.txt\",\"content\":\"data\"}"));
        assertFalse(result.error());
        assertEquals("data", Files.readString(root.resolve("sub/deep.txt")));
    }

    @Test
    void listDirListsEntries() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Files.writeString(root.resolve("a.txt"), "");
        Files.writeString(root.resolve("b.txt"), "");
        ListDirTool tool = new ListDirTool(root);
        ToolResult result = tool.execute(JSON.readTree("{\"path\":\".\"}"));
        assertFalse(result.error());
        assertTrue(result.content().contains("a.txt"));
        assertTrue(result.content().contains("b.txt"));
    }

    @Test
    void globMatchesRecursively() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Files.createDirectory(root.resolve("src"));
        Files.writeString(root.resolve("src/Main.java"), "");
        Files.writeString(root.resolve("src/Util.java"), "");
        GlobTool tool = new GlobTool(root);
        ToolResult result = tool.execute(JSON.readTree("{\"pattern\":\"src/**/*.java\"}"));
        assertFalse(result.error());
        assertTrue(result.content().contains("src/Main.java"));
        assertTrue(result.content().contains("src/Util.java"));
    }

    @Test
    void readFileRejectsSymlinkEscape() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Path secret = tempDir.resolve("secret.txt");
        Files.writeString(secret, "secret");
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), secret);
            ReadFileTool tool = new ReadFileTool(root);
            ToolResult result = tool.execute(JSON.readTree("{\"path\":\"link.txt\"}"));
            assertTrue(result.error());
        } catch (UnsupportedOperationException e) {
            // filesystem without symlink support: skip
        }
    }

    @Test
    void writeFileRejectsSymlinkTargetEscape() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Path secret = tempDir.resolve("secret.txt");
        Files.writeString(secret, "original");
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), secret);
            WriteFileTool tool = new WriteFileTool(root);
            ToolResult result = tool.execute(JSON.readTree("{\"path\":\"link.txt\",\"content\":\"PWNED\"}"));
            assertTrue(result.error());
            assertEquals("original", Files.readString(secret));
        } catch (UnsupportedOperationException e) {
            // filesystem without symlink support: skip
        }
    }

    @Test
    void writeFileRejectsDanglingSymlinkEscape() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Path outsideDir = Files.createDirectory(tempDir.resolve("outside"));
        Path outsideTarget = outsideDir.resolve("new.txt");
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), outsideTarget);
            WriteFileTool tool = new WriteFileTool(root);
            ToolResult result = tool.execute(JSON.readTree("{\"path\":\"link.txt\",\"content\":\"PWNED\"}"));
            assertTrue(result.error());
            assertFalse(Files.exists(outsideTarget));
        } catch (UnsupportedOperationException e) {
            // filesystem without symlink support: skip
        }
    }

    @Test
    void missingArgumentThrowsToolException() {
        ReadFileTool tool = new ReadFileTool(tempDir);
        assertThrows(ToolException.class, () -> tool.execute(JSON.readTree("{}")));
    }
}
