package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ListDirTool implements Tool {

    private static final ObjectMapper JSON = Json.MAPPER;

    private final Path root;

    public ListDirTool() {
        this(Path.of("").toAbsolutePath());
    }

    public ListDirTool(Path root) {
        this.root = root;
    }

    @Override
    public String name() {
        return "list_dir";
    }

    @Override
    public String description() {
        return "List the entries of a directory inside the working directory.";
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
        Path dir;
        try {
            dir = ToolPaths.requireCanonicalWithin(root, ToolPaths.requireWithin(root, pathArg));
        } catch (ToolException e) {
            return new ToolResult(e.getMessage(), true);
        }
        if (!Files.isDirectory(dir)) {
            return new ToolResult("not a directory: " + dir, true);
        }
        try (Stream<Path> stream = Files.list(dir)) {
            String listing = stream.map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.joining("\n"));
            return new ToolResult(listing.isEmpty() ? "(empty)" : listing, false);
        } catch (IOException e) {
            throw new ToolException("could not list directory: " + e.getMessage(), e);
        }
    }
}
