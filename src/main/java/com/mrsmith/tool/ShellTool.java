package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ShellTool implements Tool {

    private static final ObjectMapper JSON = Json.MAPPER;

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
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
            CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ToolResult("shell command timed out after " + timeoutMillis + "ms", true);
            }
            String stdout = out.join();
            String stderr = err.join();
            int code = process.exitValue();
            String body = code == 0 ? stdout : (stdout.isBlank() ? stderr : stdout + "\n" + stderr);
            if (code != 0 && !body.isBlank()) {
                body = body + "\nexit code " + code;
            } else if (code != 0) {
                body = "exit code " + code;
            }
            return new ToolResult(body, code != 0);
        } catch (IOException e) {
            throw new ToolException("could not run command: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("command interrupted", e);
        }
    }

    private static String readAll(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(failed to read output: " + e.getMessage() + ")";
        }
    }
}
