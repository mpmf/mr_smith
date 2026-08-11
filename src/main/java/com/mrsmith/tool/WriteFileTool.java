package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class WriteFileTool implements Tool {

    private static final ObjectMapper JSON = Json.MAPPER;

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
    public Tool.ApprovalCheck approvalCheck(JsonNode args) {
        String pathArg = args.path("path").asText(null);
        if (pathArg == null || pathArg.isBlank()) {
            return null;
        }
        try {
            Path target = ToolPaths.requireWithin(root, pathArg);
            if (SensitivePaths.isSensitive(target)) {
                return new Tool.ApprovalCheck(List.of(name()), "sensitive file");
            }
            if (Files.exists(target)) {
                target = ToolPaths.requireCanonicalWithin(root, target);
                if (SensitivePaths.isSensitive(target)) {
                    return new Tool.ApprovalCheck(List.of(name()), "sensitive file");
                }
            }
            return null;
        } catch (ToolException e) {
            return null;
        }
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
            Path ancestor = target;
            while (ancestor != null && !Files.exists(ancestor) && !Files.isSymbolicLink(ancestor)) {
                ancestor = ancestor.getParent();
            }
            if (ancestor != null) {
                ToolPaths.requireCanonicalWithin(root, ancestor);
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            AtomicFiles.write(target, content.getBytes(StandardCharsets.UTF_8));
        } catch (ToolException e) {
            return new ToolResult(e.getMessage(), true);
        } catch (IOException e) {
            throw new ToolException("could not write file: " + e.getMessage(), e);
        }
        return new ToolResult("wrote " + root.relativize(target) + " (" + content.length() + " chars)", false);
    }
}
