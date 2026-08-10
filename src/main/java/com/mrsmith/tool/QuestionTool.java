package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.io.IO;
import com.mrsmith.util.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class QuestionTool implements Tool {

    private static final ObjectMapper JSON = Json.MAPPER;

    private final IO io;

    public QuestionTool(IO io) {
        this.io = io;
    }

    @Override
    public String name() {
        return "question";
    }

    @Override
    public String description() {
        return "Ask the user one or more multiple-choice questions and return the answers. "
                + "Answer by option number, or type a free-text answer.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode questions = schema.putObject("properties").putObject("questions");
        questions.put("type", "array");
        ObjectNode item = questions.putObject("items");
        item.put("type", "object");
        ObjectNode properties = item.putObject("properties");
        properties.putObject("question").put("type", "string");
        properties.putObject("header").put("type", "string");
        ObjectNode options = properties.putObject("options");
        options.put("type", "array");
        ObjectNode opt = options.putObject("items");
        opt.put("type", "object");
        ObjectNode optProps = opt.putObject("properties");
        optProps.putObject("label").put("type", "string");
        optProps.putObject("description").put("type", "string");
        opt.putArray("required").add("label");
        properties.putObject("multiple").put("type", "boolean");
        item.putArray("required").add("question");
        schema.putArray("required").add("questions");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        JsonNode questionsNode = args.path("questions");
        if (!questionsNode.isArray() || questionsNode.isEmpty()) {
            throw new ToolException("missing required 'questions' argument");
        }
        ArrayNode answers = JSON.createArrayNode();
        int index = 0;
        for (JsonNode question : questionsNode) {
            ToolResult answerResult = askOne(question, index);
            if (answerResult.error()) {
                return answerResult;
            }
            try {
                answers.add(JSON.readTree(answerResult.content()));
            } catch (IOException e) {
                throw new ToolException("could not encode answer", e);
            }
            index++;
        }
        return new ToolResult(answers.toString(), false);
    }

    private ToolResult askOne(JsonNode question, int index) {
        String text = question.path("question").asText(null);
        if (text == null || text.isBlank()) {
            throw new ToolException("question at index " + index + " has blank question text");
        }
        String header = question.path("header").asText(null);
        io.writeLine(header == null || header.isBlank() ? text : "[" + header + "] " + text);
        List<JsonNode> options = new ArrayList<>();
        for (JsonNode option : question.path("options")) {
            options.add(option);
        }
        for (int i = 0; i < options.size(); i++) {
            io.writeLine("  " + (i + 1) + ". " + options.get(i).path("label").asText());
            String description = options.get(i).path("description").asText(null);
            if (description != null && !description.isBlank()) {
                io.writeLine("    " + description);
            }
        }
        boolean multiple = question.path("multiple").asBoolean(false);
        String answer = readAnswer().trim();
        if (answer.matches("\\d{1,9}")) {
            int n = Integer.parseInt(answer) - 1;
            if (n < 0 || n >= options.size()) {
                return new ToolResult(answer + " is not a valid option", true);
            }
            return new ToolResult(encode(options.get(n).path("label").asText()), false);
        }
        if (multiple && answer.matches("\\d{1,9}(\\s*,\\s*\\d{1,9})+")) {
            ArrayNode picked = JSON.createArrayNode();
            for (String part : answer.split(",")) {
                String p = part.trim();
                int n = Integer.parseInt(p) - 1;
                if (n < 0 || n >= options.size()) {
                    return new ToolResult(p + " is not a valid option", true);
                }
                picked.add(options.get(n).path("label").asText());
            }
            return new ToolResult(picked.toString(), false);
        }
        return new ToolResult(encode(answer), false);
    }

    private static String encode(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "\"\"";
        }
    }

    private String readAnswer() {
        try {
            String line = io.readLine();
            return line == null ? "" : line;
        } catch (IOException e) {
            throw new ToolException("could not read answer", e);
        }
    }
}
