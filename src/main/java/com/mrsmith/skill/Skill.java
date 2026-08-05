package com.mrsmith.skill;

import java.nio.file.Path;

public record Skill(String name, String description, String body, Path resourceDir) {
}
