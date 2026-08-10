package com.mrsmith.tool;

import com.mrsmith.config.ShellConfig;
import com.mrsmith.io.IO;
import com.mrsmith.skill.SkillCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public final class ToolRegistry implements ToolState {

    private static final Map<String, Function<IO, Tool>> BUILT_INS = new LinkedHashMap<>();

    static {
        BUILT_INS.put("shell", io -> new ShellTool());
        BUILT_INS.put("read_file", io -> new ReadFileTool());
        BUILT_INS.put("write_file", io -> new WriteFileTool());
        BUILT_INS.put("list_dir", io -> new ListDirTool());
        BUILT_INS.put("glob", io -> new GlobTool());
        BUILT_INS.put("web_fetch", WebFetchTool::new);
    }

    private final List<Tool> tools;
    private final Map<String, Tool> byName;

    public ToolRegistry(List<Tool> tools) {
        this.tools = List.copyOf(tools);
        Map<String, Tool> index = new LinkedHashMap<>();
        for (Tool tool : tools) {
            index.put(tool.name(), tool);
        }
        this.byName = Map.copyOf(index);
    }

    public static ToolRegistry with(List<String> toolNames, SkillCatalog catalog, IO io, TaskRunner taskRunner) {
        return with(toolNames, catalog, io, taskRunner, ShellConfig.empty());
    }

    public static ToolRegistry with(List<String> toolNames, SkillCatalog catalog, IO io, TaskRunner taskRunner,
                                    ShellConfig shellConfig) {
        ShellCommandClassifier classifier = new ShellCommandClassifier(shellConfig);
        List<Tool> tools = new ArrayList<>();
        for (String name : toolNames) {
            if (name.equals("shell")) {
                tools.add(new ShellTool(classifier));
                continue;
            }
            Function<IO, Tool> factory = BUILT_INS.get(name);
            if (factory == null) {
                throw new ToolException("Unknown tool: " + name);
            }
            tools.add(factory.apply(io));
        }
        tools.add(new EditTool());
        tools.add(new TodowriteTool());
        tools.add(new QuestionTool(io));
        if (taskRunner != null) {
            tools.add(new TaskTool(taskRunner));
        }
        if (catalog != null && !catalog.isEmpty()) {
            tools.add(new SkillTool(catalog));
        }
        return new ToolRegistry(tools);
    }

    public static Set<String> builtinNames() {
        return BUILT_INS.keySet();
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<Tool> tools() {
        return tools;
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    public void resetSession() {
        for (Tool tool : tools) {
            if (tool instanceof Resettable resettable) {
                resettable.reset();
            }
        }
    }

    @Override
    public Set<String> loadedSkills() {
        return skillTool().map(SkillTool::loaded).orElse(Set.of());
    }

    @Override
    public SkillTool.SkillLoad loadSkill(String name) {
        return skillTool().map(tool -> tool.loadSkill(name))
                .orElseGet(() -> SkillTool.SkillLoad.unknown(name));
    }

    @Override
    public List<TodowriteTool.Task> tasks() {
        Tool tool = byName.get("todowrite");
        return tool instanceof TodowriteTool todo ? todo.tasks() : List.of();
    }

    private Optional<SkillTool> skillTool() {
        Tool tool = byName.get("skill");
        return tool instanceof SkillTool skillTool ? Optional.of(skillTool) : Optional.empty();
    }
}
