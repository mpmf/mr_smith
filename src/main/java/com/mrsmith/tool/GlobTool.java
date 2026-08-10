package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class GlobTool implements Tool {

    private static final ObjectMapper JSON = Json.MAPPER;

    private final Path root;

    public GlobTool() {
        this(Path.of("").toAbsolutePath());
    }

    public GlobTool(Path root) {
        this.root = root;
    }

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "Find files matching a glob pattern relative to the working directory, e.g. src/**/*.java.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("pattern").put("type", "string");
        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String pattern = args.path("pattern").asText(null);
        if (pattern == null || pattern.isBlank()) {
            throw new ToolException("missing required 'pattern' argument");
        }
        Pattern regex = Pattern.compile(globToRegex(pattern));
        try (Stream<Path> stream = Files.walk(root)) {
            List<String> matches = stream.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p))
                    .map(p -> p.toString().replace('\\', '/'))
                    .filter(p -> regex.matcher(p).matches())
                    .sorted()
                    .toList();
            return new ToolResult(matches.isEmpty() ? "(no matches)" : String.join("\n", matches), false);
        } catch (IOException e) {
            throw new ToolException("could not glob: " + e.getMessage(), e);
        }
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                        regex.append("(?:[^/]+/)*");
                        i += 3;
                    } else {
                        regex.append(".*");
                        i += 2;
                    }
                } else {
                    regex.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return regex.toString();
    }
}
