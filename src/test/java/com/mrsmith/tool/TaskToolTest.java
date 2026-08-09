package com.mrsmith.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void delegatesToRunnerAndFormatsResult() throws Exception {
        TaskRunner runner = (prompt, agent, taskId) -> new TaskResult("subagent-1", "all done", false);
        TaskTool tool = new TaskTool(runner);
        ToolResult result = tool.execute(JSON.readTree(
                "{\"description\":\"summarize\",\"prompt\":\"do the work\"}"));
        assertFalse(result.error());
        assertEquals("Subagent subagent-1: all done", result.content());
    }

    @Test
    void passesAgentAndTaskIdThrough() throws Exception {
        TaskRunner runner = (prompt, agent, taskId) ->
                new TaskResult("subagent-3", prompt + "/" + agent + "/" + taskId, false);
        TaskTool tool = new TaskTool(runner);
        ToolResult result = tool.execute(JSON.readTree(
                "{\"description\":\"x\",\"prompt\":\"p\",\"agent\":\"b\",\"task_id\":\"subagent-3\"}"));
        assertEquals("Subagent subagent-3: p/b/subagent-3", result.content());
    }

    @Test
    void runnerErrorBecomesErrorResult() throws Exception {
        TaskRunner runner = (prompt, agent, taskId) -> new TaskResult(null, "Unknown agent: nope", true);
        TaskTool tool = new TaskTool(runner);
        ToolResult result = tool.execute(JSON.readTree(
                "{\"description\":\"x\",\"prompt\":\"p\"}"));
        assertTrue(result.error());
        assertEquals("Unknown agent: nope", result.content());
    }

    @Test
    void missingArgumentsThrow() {
        TaskTool tool = new TaskTool((p, a, t) -> new TaskResult(null, "x", true));
        assertThrows(ToolException.class, () -> tool.execute(JSON.readTree("{}")));
    }

    @Test
    void nameIsTaskAndReadOnly() {
        TaskTool tool = new TaskTool((p, a, t) -> new TaskResult(null, "", true));
        assertEquals("task", tool.name());
        assertTrue(tool.isReadOnly());
    }
}
