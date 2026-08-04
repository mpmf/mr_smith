package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class ShellTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path workDir;
    private final long timeoutMillis;

    public ShellTool() {
        this(Path.of("").toAbsolutePath(), 30_000L);
    }

    public ShellTool(Path workDir, long timeoutMillis) {
        this.workDir = workDir;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public String name() {
        return "shell";
    }

    @Override
    public String description() {
        return "Run a shell command via bash -c in the working directory and return its stdout, stderr, and exit code.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("command").put("type", "string");
        schema.putArray("required").add("command");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String command = args.path("command").asText(null);
        if (command == null || command.isBlank()) {
            throw new ToolException("missing required 'command' argument");
        }
        try {
            Process process = new ProcessBuilder("bash", "-c", command)
                    .directory(workDir.toFile())
                    .start();
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new ToolResult("shell command timed out after " + timeoutMillis + "ms", true);
            }
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.exitValue();
            String body = code == 0 ? out : (out.isBlank() ? err : out + "\n" + err);
            if (code != 0 && !body.isBlank()) {
                body = body + "\nexit code " + code;
            } else if (code != 0) {
                body = "exit code " + code;
            }
            return new ToolResult(body, code != 0);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("could not run command: " + e.getMessage(), e);
        }
    }
}
