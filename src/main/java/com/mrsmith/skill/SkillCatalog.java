package com.mrsmith.skill;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

public final class SkillCatalog {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final String SKILL_FILE = "SKILL.md";

    private final Map<String, Skill> skills;

    private SkillCatalog(Map<String, Skill> skills) {
        this.skills = Collections.unmodifiableMap(skills);
    }

    public static SkillCatalog discover(Path projectDir, Path globalDir) {
        Map<String, Skill> found = new TreeMap<>();
        loadFrom(globalDir, found);
        loadFrom(projectDir, found);
        return new SkillCatalog(found);
    }

    private static void loadFrom(Path root, Map<String, Skill> found) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path dir : entries) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                Path skillFile = dir.resolve(SKILL_FILE);
                if (!Files.isRegularFile(skillFile)) {
                    continue;
                }
                try {
                    String content = Files.readString(skillFile);
                    Optional<SkillFrontmatter.Parsed> parsed = SkillFrontmatter.parse(content);
                    if (parsed.isEmpty()) {
                        warn("Skipping malformed skill at " + skillFile);
                        continue;
                    }
                    String name = parsed.get().name();
                    String dirName = dir.getFileName().toString();
                    if (!NAME_PATTERN.matcher(name).matches() || !name.equals(dirName)) {
                        warn("Skipping skill with invalid name at " + skillFile);
                        continue;
                    }
                    found.put(name, new Skill(name, parsed.get().description(),
                            bodyAfter(content), dir));
                } catch (IOException e) {
                    warn("Skipping unreadable skill at " + skillFile + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            warn("Could not list skills in " + root + ": " + e.getMessage());
        }
    }

    private static String bodyAfter(String content) {
        String[] lines = content.split("\n", -1);
        int close = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                close = i;
                break;
            }
        }
        if (close == -1) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        for (int i = close + 1; i < lines.length; i++) {
            if (body.length() > 0) {
                body.append("\n");
            }
            body.append(lines[i]);
        }
        return body.toString();
    }

    private static void warn(String message) {
        System.err.println("Warning: " + message);
    }

    public Set<String> names() {
        return skills.keySet();
    }

    public Optional<Skill> find(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public boolean isEmpty() {
        return skills.isEmpty();
    }

    public String indexText() {
        StringBuilder sb = new StringBuilder("Available skills:");
        for (Skill skill : skills.values()) {
            sb.append("\n- ").append(skill.name()).append(": ").append(skill.description());
        }
        return sb.toString();
    }

    public String render(String name) {
        Skill skill = skills.get(name);
        if (skill == null) {
            throw new IllegalArgumentException("Unknown skill: " + name);
        }
        return "## " + skill.name() + "\n" + skill.description()
                + "\nResources at: " + skill.resourceDir() + "\n\n" + skill.body();
    }
}
