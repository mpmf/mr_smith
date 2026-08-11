package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ListDirToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode args(String path) {
        ObjectNode node = JSON.createObjectNode();
        node.put("path", path);
        return node;
    }

    @Test
    void noCheckForEmptyDir(@TempDir Path root) {
        assertNull(new ListDirTool(root).approvalCheck(args(".")));
    }

    @Test
    void noCheckForDirWithNormalFiles(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("README.md"), "hi");
        assertNull(new ListDirTool(root).approvalCheck(args(".")));
    }

    @Test
    void checkForDirContainingDotEnv(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".env"), "SECRET=1");
        assertNotNull(new ListDirTool(root).approvalCheck(args(".")));
    }

    @Test
    void checkForSensitiveDirItself(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".env"));
        assertNotNull(new ListDirTool(root).approvalCheck(args(".env")));
    }

    @Test
    void noCheckForMissingDir(@TempDir Path root) {
        assertNull(new ListDirTool(root).approvalCheck(args("nope")));
    }
}
