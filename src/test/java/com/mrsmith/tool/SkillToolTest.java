package com.mrsmith.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrsmith.skill.SkillCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private SkillCatalog catalog(String name, String description, String body) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body);
        return SkillCatalog.discover(tempDir, tempDir.resolve("nope"));
    }

    @Test
    void returnsRenderedBody() throws IOException {
        SkillTool tool = new SkillTool(catalog("coding", "Write Java.", "run tests"));
        ToolResult result = tool.execute(JSON.readTree("{\"name\":\"coding\"}"));
        assertFalse(result.error());
        assertTrue(result.content().startsWith("## coding"));
        assertTrue(result.content().contains("run tests"));
    }

    @Test
    void unknownSkillIsError() throws IOException {
        SkillTool tool = new SkillTool(catalog("coding", "Write Java.", "b"));
        ToolResult result = tool.execute(JSON.readTree("{\"name\":\"nope\"}"));
        assertTrue(result.error());
        assertEquals("Unknown skill: nope", result.content());
    }

    @Test
    void missingNameArgumentThrows() throws IOException {
        SkillTool tool = new SkillTool(catalog("coding", "Write Java.", "b"));
        assertThrows(ToolException.class, () -> tool.execute(JSON.readTree("{}")));
    }

    @Test
    void secondCallReportsAlreadyLoaded() throws IOException {
        SkillTool tool = new SkillTool(catalog("coding", "Write Java.", "b"));
        tool.execute(JSON.readTree("{\"name\":\"coding\"}"));
        ToolResult second = tool.execute(JSON.readTree("{\"name\":\"coding\"}"));
        assertFalse(second.error());
        assertEquals("Skill 'coding' is already loaded.", second.content());
    }

    @Test
    void resetClearsLoadedState() throws IOException {
        SkillTool tool = new SkillTool(catalog("coding", "Write Java.", "b"));
        tool.execute(JSON.readTree("{\"name\":\"coding\"}"));
        tool.reset();
        ToolResult again = tool.execute(JSON.readTree("{\"name\":\"coding\"}"));
        assertFalse(again.error());
        assertTrue(again.content().startsWith("## coding"));
    }

    @Test
    void tracksLoadedNames() throws IOException {
        SkillTool tool = new SkillTool(catalog("coding", "Write Java.", "b"));
        assertTrue(tool.load("coding"));
        assertTrue(tool.isLoaded("coding"));
        assertFalse(tool.load("coding"));
    }
}
