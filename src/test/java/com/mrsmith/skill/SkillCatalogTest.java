package com.mrsmith.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillCatalogTest {

    @TempDir
    Path tempDir;

    private void writeSkill(Path dir, String name, String description, String body) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body);
    }

    @Test
    void emptyWhenNoSkillDirs() {
        SkillCatalog catalog = SkillCatalog.discover(tempDir.resolve("project"), tempDir.resolve("global"));
        assertTrue(catalog.isEmpty());
    }

    @Test
    void discoversProjectSkills() throws IOException {
        writeSkill(tempDir.resolve("skills").resolve("coding"), "coding", "Write Java.", "body text");
        SkillCatalog catalog = SkillCatalog.discover(tempDir.resolve("skills"), tempDir.resolve("nope"));
        assertFalse(catalog.isEmpty());
        assertTrue(catalog.find("coding").isPresent());
        assertEquals("Write Java.", catalog.find("coding").get().description());
    }

    @Test
    void discoversGlobalSkills() throws IOException {
        writeSkill(tempDir.resolve("skills").resolve("git-release"), "git-release", "Releases.", "body");
        SkillCatalog catalog = SkillCatalog.discover(tempDir.resolve("nope"), tempDir.resolve("skills"));
        assertTrue(catalog.find("git-release").isPresent());
    }

    @Test
    void projectWinsOnNameCollision() throws IOException {
        writeSkill(tempDir.resolve("global").resolve("coding"), "coding", "global version", "g");
        writeSkill(tempDir.resolve("project").resolve("coding"), "coding", "project version", "p");
        SkillCatalog catalog = SkillCatalog.discover(tempDir.resolve("project"), tempDir.resolve("global"));
        assertEquals("project version", catalog.find("coding").get().description());
    }

    @Test
    void skipsSkillWithMissingDescription() throws IOException {
        Path dir = tempDir.resolve("skills").resolve("bad");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: bad\n---\nbody");
        SkillCatalog catalog = SkillCatalog.discover(tempDir.resolve("skills"), tempDir.resolve("nope"));
        assertTrue(catalog.isEmpty());
    }

    @Test
    void skipsSkillWhoseDirectoryDoesNotMatchName() throws IOException {
        Path dir = tempDir.resolve("skills").resolve("mismatch");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: coding\ndescription: x\n---\nbody");
        SkillCatalog catalog = SkillCatalog.discover(tempDir.resolve("skills"), tempDir.resolve("nope"));
        assertTrue(catalog.isEmpty());
    }

    @Test
    void namesAreSorted() throws IOException {
        writeSkill(tempDir.resolve("skills").resolve("zeta"), "zeta", "z", "b");
        writeSkill(tempDir.resolve("skills").resolve("alpha"), "alpha", "a", "b");
        SkillCatalog catalog = SkillCatalog.discover(tempDir.resolve("skills"), tempDir.resolve("nope"));
        assertEquals(List.of("alpha", "zeta"), catalog.names().stream().toList());
    }

    @Test
    void indexTextListsNameAndDescription() throws IOException {
        writeSkill(tempDir.resolve("skills").resolve("coding"), "coding", "Write Java.", "b");
        SkillCatalog catalog = SkillCatalog.discover(tempDir.resolve("skills"), tempDir.resolve("nope"));
        assertEquals("Available skills:\n- coding: Write Java.", catalog.indexText());
    }

    @Test
    void renderIncludesHeaderAndResourceDir() throws IOException {
        Path dir = tempDir.resolve("skills").resolve("coding");
        writeSkill(dir, "coding", "Write Java.", "run tests");
        SkillCatalog catalog = SkillCatalog.discover(tempDir.resolve("skills"), tempDir.resolve("nope"));
        String rendered = catalog.render("coding");
        assertTrue(rendered.startsWith("## coding\nWrite Java.\nResources at: "));
        assertTrue(rendered.endsWith("run tests"));
        assertTrue(rendered.contains(dir.toString()));
    }
}
