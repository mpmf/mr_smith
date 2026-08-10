package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class EditTool implements Tool {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final long MAX_BYTES = 1_048_576;

    private final Path root;

    public EditTool() {
        this(Path.of("").toAbsolutePath());
    }

    public EditTool(Path root) {
        this.root = root;
    }

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String description() {
        return "Replace an exact substring in a file. Fails unless oldString occurs exactly once (or replaceAll is true).";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("filePath").put("type", "string");
        properties.putObject("oldString").put("type", "string");
        properties.putObject("newString").put("type", "string");
        properties.putObject("replaceAll").put("type", "boolean");
        schema.putArray("required").add("filePath").add("oldString").add("newString");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String pathArg = args.path("filePath").asText(null);
        if (pathArg == null || pathArg.isBlank()) {
            throw new ToolException("missing required 'filePath' argument");
        }
        String oldString = args.path("oldString").asText(null);
        if (oldString == null || oldString.isBlank()) {
            throw new ToolException("missing required 'oldString' argument");
        }
        String newString = args.path("newString").asText(null);
        if (newString == null) {
            throw new ToolException("missing required 'newString' argument");
        }
        if (newString.equals(oldString)) {
            return new ToolResult("newString must differ from oldString", true);
        }
        boolean replaceAll = args.path("replaceAll").asBoolean(false);
        try {
            Path target = ToolPaths.requireWithin(root, pathArg);
            if (!Files.isRegularFile(target)) {
                return new ToolResult("file not found: " + pathArg, true);
            }
            Path real = ToolPaths.requireCanonicalWithin(root, target);
            if (Files.size(real) > MAX_BYTES) {
                return new ToolResult("file too large to edit (max " + MAX_BYTES + " bytes)", true);
            }
            byte[] bytes = Files.readAllBytes(real);
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (!Arrays.equals(content.getBytes(StandardCharsets.UTF_8), bytes)) {
                return new ToolResult("file is not valid UTF-8; refusing to edit", true);
            }
            int count = countOccurrences(content, oldString);
            if (count == 0) {
                return new ToolResult("oldString not found in file", true);
            }
            if (count > 1 && !replaceAll) {
                return new ToolResult("found " + count
                        + " matches; set replaceAll=true or provide a more specific oldString", true);
            }
            String updated = count == 1
                    ? replaceFirst(content, oldString, newString)
                    : content.replace(oldString, newString);
            AtomicFiles.write(real, updated.getBytes(StandardCharsets.UTF_8));
            return new ToolResult("Edited " + root.relativize(target) + " (" + count + " replacements)", false);
        } catch (ToolException e) {
            return new ToolResult(e.getMessage(), true);
        } catch (IOException e) {
            throw new ToolException("could not edit file: " + e.getMessage(), e);
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static String replaceFirst(String text, String oldString, String newString) {
        int idx = text.indexOf(oldString);
        return text.substring(0, idx) + newString + text.substring(idx + oldString.length());
    }
}
