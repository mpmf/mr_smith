package com.mrsmith.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ToolRegistry {

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

    public static ToolRegistry with(List<String> toolNames) {
        List<Tool> tools = new ArrayList<>();
        for (String name : toolNames) {
            switch (name) {
                case "shell" -> tools.add(new ShellTool());
                case "read_file" -> tools.add(new ReadFileTool());
                case "write_file" -> tools.add(new WriteFileTool());
                case "list_dir" -> tools.add(new ListDirTool());
                case "glob" -> tools.add(new GlobTool());
                case "web_fetch" -> tools.add(new WebFetchTool());
                default -> throw new ToolException("Unknown tool: " + name);
            }
        }
        return new ToolRegistry(tools);
    }

    public static Set<String> builtinNames() {
        return Set.of("shell", "read_file", "write_file", "list_dir", "glob", "web_fetch");
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
}
