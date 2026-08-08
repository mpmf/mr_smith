package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodowriteToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void replacesListAndReturnsIt() throws Exception {
        TodowriteTool tool = new TodowriteTool();
        ToolResult result = tool.execute(JSON.readTree(
                "{\"todos\":[{\"content\":\"a\",\"status\":\"in_progress\",\"priority\":\"high\"},"
                + "{\"content\":\"b\",\"status\":\"pending\",\"priority\":\"low\"}]}"));
        assertFalse(result.error());
        assertEquals(2, tool.tasks().size());
        assertEquals("in_progress", tool.tasks().get(0).status());
        JsonNode returned = JSON.readTree(result.content());
        assertEquals(2, returned.size());
        assertEquals("a", returned.get(0).get("content").asText());
        assertEquals("pending", returned.get(1).get("status").asText());
    }

    @Test
    void replacesWholeList() throws Exception {
        TodowriteTool tool = new TodowriteTool();
        tool.execute(JSON.readTree(
                "{\"todos\":[{\"content\":\"a\",\"status\":\"completed\",\"priority\":\"high\"}]}"));
        tool.execute(JSON.readTree(
                "{\"todos\":[{\"content\":\"b\",\"status\":\"pending\",\"priority\":\"medium\"}]}"));
        assertEquals(List.of("b"),
                tool.tasks().stream().map(TodowriteTool.Task::content).toList());
    }

    @Test
    void missingTodosThrows() {
        TodowriteTool tool = new TodowriteTool();
        assertThrows(ToolException.class, () -> tool.execute(JSON.readTree("{}")));
    }

    @Test
    void invalidStatusThrows() {
        TodowriteTool tool = new TodowriteTool();
        assertThrows(ToolException.class, () -> tool.execute(JSON.readTree(
                "{\"todos\":[{\"content\":\"a\",\"status\":\"done\",\"priority\":\"high\"}]}")));
    }

    @Test
    void invalidPriorityThrows() {
        TodowriteTool tool = new TodowriteTool();
        assertThrows(ToolException.class, () -> tool.execute(JSON.readTree(
                "{\"todos\":[{\"content\":\"a\",\"status\":\"pending\",\"priority\":\"urgent\"}]}")));
    }

    @Test
    void blankContentThrows() {
        TodowriteTool tool = new TodowriteTool();
        assertThrows(ToolException.class, () -> tool.execute(JSON.readTree(
                "{\"todos\":[{\"content\":\"  \",\"status\":\"pending\",\"priority\":\"high\"}]}")));
    }

    @Test
    void resetClearsList() throws Exception {
        TodowriteTool tool = new TodowriteTool();
        tool.execute(JSON.readTree(
                "{\"todos\":[{\"content\":\"a\",\"status\":\"pending\",\"priority\":\"high\"}]}"));
        tool.reset();
        assertTrue(tool.tasks().isEmpty());
    }
}
