package com.mrsmith.skill;

import com.mrsmith.skill.SkillFrontmatter.Parsed;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillFrontmatterTest {

    @Test
    void parsesNameAndDescription() {
        String content = """
                ---
                name: coding
                description: Guidance for writing idiomatic Java.
                ---
                body here
                """;
        Optional<Parsed> parsed = SkillFrontmatter.parse(content);
        assertTrue(parsed.isPresent());
        assertEquals("coding", parsed.get().name());
        assertEquals("Guidance for writing idiomatic Java.", parsed.get().description());
    }

    @Test
    void stripsSurroundingQuotes() {
        String content = "---\nname: coding\ndescription: \"A skill.\"\n---\nbody";
        Optional<Parsed> parsed = SkillFrontmatter.parse(content);
        assertTrue(parsed.isPresent());
        assertEquals("A skill.", parsed.get().description());
    }

    @Test
    void missingFrontmatterIsEmpty() {
        assertTrue(SkillFrontmatter.parse("just body").isEmpty());
    }

    @Test
    void missingNameIsEmpty() {
        assertTrue(SkillFrontmatter.parse("---\ndescription: x\n---\nbody").isEmpty());
    }

    @Test
    void missingDescriptionIsEmpty() {
        assertTrue(SkillFrontmatter.parse("---\nname: coding\n---\nbody").isEmpty());
    }

    @Test
    void unclosedFrontmatterIsEmpty() {
        assertTrue(SkillFrontmatter.parse("---\nname: coding\ndescription: x").isEmpty());
    }
}
