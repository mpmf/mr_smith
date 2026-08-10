package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.skill.SkillCatalog;
import com.mrsmith.util.Json;

import java.util.HashSet;
import java.util.Set;

public final class SkillTool implements Tool, Resettable {

    private static final ObjectMapper JSON = Json.MAPPER;

    private final SkillCatalog catalog;
    private final Set<String> loaded = new HashSet<>();

    public SkillTool(SkillCatalog catalog) {
        this.catalog = catalog;
    }

    public record SkillLoad(boolean loaded, boolean error, String content, String message) {

        static SkillLoad unknown(String name) {
            return new SkillLoad(false, true, null, "Unknown skill: " + name);
        }

        static SkillLoad alreadyLoaded(String name) {
            return new SkillLoad(false, false, null, "Skill '" + name + "' is already loaded.");
        }

        static SkillLoad ok(String content) {
            return new SkillLoad(true, false, content, null);
        }
    }

    public Set<String> loaded() {
        return Set.copyOf(loaded);
    }

    public boolean load(String name) {
        return loaded.add(name);
    }

    public SkillLoad loadSkill(String name) {
        if (catalog.find(name).isEmpty()) {
            return SkillLoad.unknown(name);
        }
        if (!load(name)) {
            return SkillLoad.alreadyLoaded(name);
        }
        return SkillLoad.ok(catalog.render(name));
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
        SkillLoad result = loadSkill(name);
        if (result.error()) {
            return new ToolResult(result.message(), true);
        }
        return new ToolResult(result.loaded() ? result.content() : result.message(), false);
    }
}
