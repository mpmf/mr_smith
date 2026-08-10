package com.mrsmith.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void returnsStdoutAndExitCodeZero() throws Exception {
        ShellTool tool = new ShellTool(tempDir, 5000);
        ToolResult result = tool.execute(JSON.readTree("{\"command\":\"echo hi\"}"));
        assertFalse(result.error());
        assertTrue(result.content().contains("hi"));
    }

    @Test
    void capturesNonZeroExitCode() throws Exception {
        ShellTool tool = new ShellTool(tempDir, 5000);
        ToolResult result = tool.execute(JSON.readTree("{\"command\":\"echo oops && exit 3\"}"));
        assertTrue(result.error());
        assertTrue(result.content().contains("3"));
    }

    @Test
    void runsInWorkingDirectory() throws Exception {
        Files.writeString(tempDir.resolve("marker.txt"), "present");
        ShellTool tool = new ShellTool(tempDir, 5000);
        ToolResult result = tool.execute(JSON.readTree("{\"command\":\"ls marker.txt\"}"));
        assertFalse(result.error());
        assertTrue(result.content().contains("marker.txt"));
    }

    @Test
    void timesOutAndReturnsError() throws Exception {
        ShellTool tool = new ShellTool(tempDir, 200);
        ToolResult result = tool.execute(JSON.readTree("{\"command\":\"sleep 5\"}"));
        assertTrue(result.error());
        assertTrue(result.content().contains("timed out"));
    }

    @Test
    void handlesLargeOutputWithoutDeadlock() throws Exception {
        ShellTool tool = new ShellTool(tempDir, 5000);
        ToolResult result = tool.execute(JSON.readTree("{\"command\":\"perl -e 'print \\\"x\\\" x 200000'\"}"));
        assertFalse(result.error());
        assertTrue(result.content().contains("x".repeat(200000)));
    }

    @Test
    void safeCommandNeedsNoApproval() throws Exception {
        ShellTool tool = new ShellTool(tempDir, 5000);
        assertNull(tool.approvalCheck(JSON.readTree("{\"command\":\"ls marker.txt\"}")));
    }

    @Test
    void dangerousCommandNeedsApprovalWithKeyAndReason() throws Exception {
        ShellTool tool = new ShellTool(tempDir, 5000);
        Tool.ApprovalCheck check = tool.approvalCheck(JSON.readTree("{\"command\":\"rm marker.txt\"}"));
        assertNotNull(check);
        assertEquals(List.of("shell:rm"), check.keys());
        assertEquals("dangerous command", check.reason());
    }

    @Test
    void unknownCommandNeedsApprovalWithUnknownReason() throws Exception {
        ShellTool tool = new ShellTool(tempDir, 5000);
        Tool.ApprovalCheck check = tool.approvalCheck(JSON.readTree("{\"command\":\"frobnicate x\"}"));
        assertNotNull(check);
        assertEquals("unknown command", check.reason());
    }

    @Test
    void blankCommandNeedsNoApproval() throws Exception {
        ShellTool tool = new ShellTool(tempDir, 5000);
        assertNull(tool.approvalCheck(JSON.readTree("{\"command\":\"\"}")));
    }

    @Test
    void shellCommandApprovalKeysAreNamespaced() throws Exception {
        ShellTool tool = new ShellTool(tempDir, 5000);
        Tool.ApprovalCheck check = tool.approvalCheck(JSON.readTree("{\"command\":\"edit x\"}"));
        assertNotNull(check);
        assertTrue(check.keys().contains("shell:edit"));
        assertFalse(check.keys().contains("edit"));
        com.mrsmith.chat.ToolApproval approval = new com.mrsmith.chat.ToolApproval();
        approval.allowAlways("shell:edit");
        assertFalse(approval.isAlwaysAllowed("edit"));
    }
}
