package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlobToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode args(String pattern) {
        ObjectNode node = JSON.createObjectNode();
        node.put("pattern", pattern);
        return node;
    }

    @Test
    void noCheckForLiteralPath(@TempDir Path root) {
        assertNull(new GlobTool(root).approvalCheck(args("README.md")));
    }

    @Test
    void checkForStar(@TempDir Path root) {
        assertNotNull(new GlobTool(root).approvalCheck(args("*")));
    }

    @Test
    void checkForDoubleStar(@TempDir Path root) {
        assertNotNull(new GlobTool(root).approvalCheck(args("**/*.java")));
    }

    @Test
    void checkForEnvGlob(@TempDir Path root) {
        assertNotNull(new GlobTool(root).approvalCheck(args("*.env")));
    }
}
