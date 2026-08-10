package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.util.Json;

import java.util.ArrayList;
import java.util.List;

public final class TodowriteTool implements Tool, Resettable {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final List<String> STATUSES = List.of("pending", "in_progress", "completed", "cancelled");
    private static final List<String> PRIORITIES = List.of("high", "medium", "low");

    private List<Task> tasks = List.of();

    public record Task(String content, String status, String priority) {
    }

    public List<Task> tasks() {
        return List.copyOf(tasks);
    }

    @Override
    public void reset() {
        tasks = List.of();
    }

    @Override
    public String name() {
        return "todowrite";
    }

    @Override
    public String description() {
        return "Replace the session task list with the given todos. "
                + "Status is one of pending, in_progress, completed, cancelled; "
                + "priority one of high, medium, low. "
                + "Keep exactly one in_progress while work remains, update status in real time, "
                + "and mark completed only when the work (including verification) is actually done.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode todos = schema.putObject("properties").putObject("todos");
        todos.put("type", "array");
        ObjectNode item = todos.putObject("items");
        item.put("type", "object");
        ObjectNode properties = item.putObject("properties");
        properties.putObject("content").put("type", "string");
        ObjectNode status = properties.putObject("status");
        status.put("type", "string");
        status.putArray("enum").addAll(toTextArray(STATUSES));
        ObjectNode priority = properties.putObject("priority");
        priority.put("type", "string");
        priority.putArray("enum").addAll(toTextArray(PRIORITIES));
        item.putArray("required").add("content").add("status").add("priority");
        schema.putArray("required").add("todos");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        JsonNode todosNode = args.path("todos");
        if (!todosNode.isArray()) {
            throw new ToolException("missing required 'todos' argument");
        }
        List<Task> next = new ArrayList<>();
        int index = 0;
        for (JsonNode node : todosNode) {
            String content = node.path("content").asText(null);
            String status = node.path("status").asText(null);
            String priority = node.path("priority").asText(null);
            if (content == null || content.isBlank()) {
                throw new ToolException("todo at index " + index + " has blank content");
            }
            if (status == null || !STATUSES.contains(status)) {
                throw new ToolException("todo at index " + index + " has invalid status: " + status);
            }
            if (priority == null || !PRIORITIES.contains(priority)) {
                throw new ToolException("todo at index " + index + " has invalid priority: " + priority);
            }
            next.add(new Task(content, status, priority));
            index++;
        }
        tasks = List.copyOf(next);
        return new ToolResult(toJson(tasks), false);
    }

    private static String toJson(List<Task> list) {
        ArrayNode arr = JSON.createArrayNode();
        for (Task task : list) {
            ObjectNode o = arr.addObject();
            o.put("content", task.content());
            o.put("status", task.status());
            o.put("priority", task.priority());
        }
        return arr.toString();
    }

    private static ArrayNode toTextArray(List<String> values) {
        ArrayNode arr = JSON.createArrayNode();
        for (String value : values) {
            arr.add(value);
        }
        return arr;
    }
}
