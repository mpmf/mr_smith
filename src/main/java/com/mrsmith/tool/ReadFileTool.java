package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ReadFileTool implements Tool {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final long MAX_BYTES = 1_048_576;

    private final Path root;

    public ReadFileTool() {
        this(Path.of("").toAbsolutePath());
    }

    public ReadFileTool(Path root) {
        this.root = root;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read the contents of a file inside the working directory.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("path").put("type", "string");
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String pathArg = args.path("path").asText(null);
        if (pathArg == null || pathArg.isBlank()) {
            throw new ToolException("missing required path argument");
        }
        Path target;
        try {
            target = ToolPaths.requireWithin(root, pathArg);
            target = ToolPaths.requireCanonicalWithin(root, target);
        } catch (ToolException e) {
            return new ToolResult(e.getMessage(), true);
        }
        try {
            if (Files.size(target) > MAX_BYTES) {
                return new ToolResult("file exceeds " + MAX_BYTES + " bytes", true);
            }
            return new ToolResult(Files.readString(target), false);
        } catch (IOException e) {
            throw new ToolException("could not read file: " + e.getMessage(), e);
        }
    }
}
