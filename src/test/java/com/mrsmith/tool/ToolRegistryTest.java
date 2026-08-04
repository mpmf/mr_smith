package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static class StubTool implements Tool {
        private final String name;
        private final boolean readOnly;
        int calls = 0;

        StubTool(String name, boolean readOnly) {
            this.name = name;
            this.readOnly = readOnly;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public JsonNode parametersSchema() {
            return JSON.createObjectNode();
        }

        @Override
        public boolean isReadOnly() {
            return readOnly;
        }

        @Override
        public ToolResult execute(JsonNode args) {
            calls++;
            return new ToolResult("result", false);
        }
    }

    @Test
    void findsEnabledToolsByName() {
        StubTool shell = new StubTool("shell", false);
        ToolRegistry registry = new ToolRegistry(List.of(shell));
        assertEquals(Optional.of(shell), registry.find("shell"));
        assertTrue(registry.find("shell").isPresent());
        assertFalse(registry.find("web_fetch").isPresent());
    }

    @Test
    void exposesEnabledToolsInOrder() {
        StubTool a = new StubTool("read_file", true);
        StubTool b = new StubTool("shell", false);
        ToolRegistry registry = new ToolRegistry(List.of(b, a));
        assertEquals(List.of("shell", "read_file"),
                registry.tools().stream().map(Tool::name).toList());
    }

    @Test
    void emptyRegistryReportsEmpty() {
        ToolRegistry registry = new ToolRegistry(List.of());
        assertTrue(registry.isEmpty());
        assertTrue(registry.tools().isEmpty());
    }

    @Test
    void builtInWithNamesCreatesAllRequestedTools() {
        ToolRegistry registry = ToolRegistry.with(List.of("shell", "read_file", "write_file", "list_dir", "glob", "web_fetch"));
        assertEquals(6, registry.tools().size());
        assertTrue(registry.find("shell").isPresent());
        assertTrue(registry.find("web_fetch").isPresent());
    }

    @Test
    void builtInWithUnknownNameThrows() {
        assertThrows(ToolException.class, () -> ToolRegistry.with(List.of("nope")));
    }

    @Test
    void builtinNamesCoversAllTools() {
        assertTrue(ToolRegistry.builtinNames().containsAll(
                List.of("shell", "read_file", "write_file", "list_dir", "glob", "web_fetch")));
    }
}
