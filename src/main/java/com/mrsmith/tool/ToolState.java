package com.mrsmith.tool;

import java.util.List;
import java.util.Set;

public interface ToolState {

    Set<String> loadedSkills();

    SkillTool.SkillLoad loadSkill(String name);

    List<TodowriteTool.Task> tasks();
}
