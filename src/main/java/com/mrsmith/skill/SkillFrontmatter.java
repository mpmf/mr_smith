package com.mrsmith.skill;

import java.util.Optional;

public final class SkillFrontmatter {

    private SkillFrontmatter() {
    }

    public record Parsed(String name, String description) {
    }

    public static Optional<Parsed> parse(String content) {
        if (content == null) {
            return Optional.empty();
        }
        String[] lines = content.split("\n", -1);
        if (lines.length == 0 || !lines[0].trim().equals("---")) {
            return Optional.empty();
        }
        int close = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                close = i;
                break;
            }
        }
        if (close == -1) {
            return Optional.empty();
        }
        String name = null;
        String description = null;
        for (int i = 1; i < close; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = lines[i].substring(0, colon).trim();
            String value = unquote(lines[i].substring(colon + 1).trim());
            if (key.equals("name")) {
                name = value;
            } else if (key.equals("description")) {
                description = value;
            }
        }
        if (name == null || name.isBlank() || description == null || description.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(name, description));
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
