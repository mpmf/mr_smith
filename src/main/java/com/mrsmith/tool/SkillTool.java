package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.skill.SkillCatalog;

import java.util.HashSet;
import java.util.Set;

public final class SkillTool implements Tool, Resettable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SkillCatalog catalog;
    private final Set<String> loaded = new HashSet<>();

    public SkillTool(SkillCatalog catalog) {
        this.catalog = catalog;
    }

    public boolean isLoaded(String name) {
        return loaded.contains(name);
    }

    public boolean load(String name) {
        return loaded.add(name);
    }

    @Override
    public void reset() {
        loaded.clear();
    }

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public String description() {
        return "Load a skill's instructions into the conversation. "
                + "Use a name from the Available skills list in the system prompt.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("name").put("type", "string");
        schema.putArray("required").add("name");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String name = args.path("name").asText(null);
        if (name == null || name.isBlank()) {
            throw new ToolException("missing required 'name' argument");
        }
        if (catalog.find(name).isEmpty()) {
            return new ToolResult("Unknown skill: " + name, true);
        }
        if (!load(name)) {
            return new ToolResult("Skill '" + name + "' is already loaded.", false);
        }
        return new ToolResult(catalog.render(name), false);
    }
}
