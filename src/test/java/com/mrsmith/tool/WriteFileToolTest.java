package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WriteFileToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode args(String path) {
        ObjectNode node = JSON.createObjectNode();
        node.put("path", path);
        node.put("content", "x");
        return node;
    }

    @Test
    void noCheckForNormalFile(@TempDir Path root) {
        assertNull(new WriteFileTool(root).approvalCheck(args("src/Main.java")));
    }

    @Test
    void checkForDotEnv(@TempDir Path root) {
        assertNotNull(new WriteFileTool(root).approvalCheck(args(".env")));
    }

    @Test
    void checkForSshKey(@TempDir Path root) {
        assertNotNull(new WriteFileTool(root).approvalCheck(args("config/id_rsa")));
    }

    @Test
    void checkForPem(@TempDir Path root) {
        assertNotNull(new WriteFileTool(root).approvalCheck(args("certs/server.pem")));
    }

    @Test
    void noCheckForEscapingPath(@TempDir Path root) {
        assertNull(new WriteFileTool(root).approvalCheck(args("../outside.txt")));
    }
}
