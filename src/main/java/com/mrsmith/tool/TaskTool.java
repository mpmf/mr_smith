package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.util.Json;

public final class TaskTool implements Tool {

    private static final ObjectMapper JSON = Json.MAPPER;

    private final TaskRunner runner;

    public TaskTool(TaskRunner runner) {
        this.runner = runner;
    }

    @Override
    public String name() {
        return "task";
    }

    @Override
    public String description() {
        return "Dispatch a sub-agent with an isolated context to work autonomously, "
                + "then return its final answer. Use for large or separable pieces of work; "
                + "do not duplicate the sub-agent's work yourself. Include the full task "
                + "description and the exact information you want back in its final message.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("description").put("type", "string");
        properties.putObject("prompt").put("type", "string");
        properties.putObject("agent").put("type", "string");
        properties.putObject("task_id").put("type", "string");
        schema.putArray("required").add("description").add("prompt");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String description = args.path("description").asText(null);
        String prompt = args.path("prompt").asText(null);
        if (description == null || description.isBlank() || prompt == null || prompt.isBlank()) {
            throw new ToolException("missing required 'description' and 'prompt' arguments");
        }
        String agent = args.path("agent").asText(null);
        String taskId = args.path("task_id").asText(null);
        TaskResult result = runner.run(prompt, agent, taskId);
        if (result.error()) {
            return new ToolResult(result.message(), true);
        }
        return new ToolResult("Subagent " + result.id() + ": " + result.message(), false);
    }
}
