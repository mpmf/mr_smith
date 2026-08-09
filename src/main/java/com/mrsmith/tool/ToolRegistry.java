package com.mrsmith.tool;

import com.mrsmith.io.IO;
import com.mrsmith.skill.SkillCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public final class ToolRegistry {

    private static final Map<String, Supplier<Tool>> BUILT_INS = new LinkedHashMap<>();

    static {
        BUILT_INS.put("shell", ShellTool::new);
        BUILT_INS.put("read_file", ReadFileTool::new);
        BUILT_INS.put("write_file", WriteFileTool::new);
        BUILT_INS.put("list_dir", ListDirTool::new);
        BUILT_INS.put("glob", GlobTool::new);
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
        List<Tool> tools = new ArrayList<>();
        for (String name : toolNames) {
            Supplier<Tool> factory = BUILT_INS.get(name);
            if (factory == null) {
                throw new ToolException("Unknown tool: " + name);
            }
            tools.add(factory.get());
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
}
