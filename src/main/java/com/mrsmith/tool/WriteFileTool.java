package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WriteFileTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path root;

    public WriteFileTool() {
        this(Path.of("").toAbsolutePath());
    }

    public WriteFileTool(Path root) {
        this.root = root;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Write content to a file inside the working directory, creating parent directories as needed.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path").put("type", "string");
        properties.putObject("content").put("type", "string");
        schema.putArray("required").add("path").add("content");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String content = args.path("content").asText(null);
        if (content == null) {
            throw new ToolException("missing required 'content' argument");
        }
        String pathArg = args.path("path").asText(null);
        if (pathArg == null || pathArg.isBlank()) {
            throw new ToolException("missing required path argument");
        }
        Path target;
        try {
            target = ToolPaths.requireWithin(root, pathArg);
            Path parent = target.getParent();
            if (parent != null && Files.exists(parent)) {
                ToolPaths.requireCanonicalWithin(root, parent);
            }
            Files.createDirectories(parent);
            Files.writeString(target, content);
        } catch (ToolException e) {
            return new ToolResult(e.getMessage(), true);
        } catch (IOException e) {
            throw new ToolException("could not write file: " + e.getMessage(), e);
        }
        return new ToolResult("wrote " + root.relativize(target) + " (" + content.length() + " chars)", false);
    }
}
