package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class WebFetchTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_CHARS = 1_048_576;

    private final HttpClient httpClient;
    private final long timeoutMillis;

    public WebFetchTool() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(), 10_000L);
    }

    public WebFetchTool(HttpClient httpClient, long timeoutMillis) {
        this.httpClient = httpClient;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public String description() {
        return "Fetch a URL over HTTP(S) and return the response body text.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("url").put("type", "string");
        schema.putArray("required").add("url");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String url = args.path("url").asText(null);
        if (url == null || url.isBlank()) {
            throw new ToolException("missing required 'url' argument");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new ToolException("url must start with http:// or https://");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("User-Agent", "mr-smith")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return new ToolResult("HTTP " + response.statusCode(), true);
            }
            String body = response.body();
            if (body.length() > MAX_CHARS) {
                body = body.substring(0, (int) MAX_CHARS) + "\n[truncated]";
            }
            return new ToolResult(body, false);
        } catch (IOException e) {
            return new ToolResult("fetch failed: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("fetch interrupted");
        }
    }
}
