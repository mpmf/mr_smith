package com.mrsmith.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.config.AppConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

public class OpenAiCompatibleProvider implements Provider {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AppConfig config;
    private final HttpClient httpClient;
    private final long retryDelayMillis;

    public OpenAiCompatibleProvider(AppConfig config) {
        this(config, HttpClient.newHttpClient(), 2000L);
    }

    OpenAiCompatibleProvider(AppConfig config, HttpClient httpClient) {
        this(config, httpClient, 2000L);
    }

    OpenAiCompatibleProvider(AppConfig config, HttpClient httpClient, long retryDelayMillis) {
        this.config = config;
        this.httpClient = httpClient;
        this.retryDelayMillis = retryDelayMillis;
    }

    @Override
    public ChatMessage send(List<ChatMessage> history, Consumer<String> tokenSink) {
        try {
            return doSend(history, tokenSink);
        } catch (ProviderException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Network error while contacting "
                    + config.baseUrl() + ": " + e.getMessage(), e);
        }
    }

    private ChatMessage doSend(List<ChatMessage> history, Consumer<String> tokenSink)
            throws IOException, InterruptedException {
        HttpRequest request = buildRequest(buildRequestBody(history));
        HttpResponse<InputStream> response = sendWithRetry(request);
        return handleResponse(response, tokenSink);
    }

    private HttpResponse<InputStream> sendWithRetry(HttpRequest request)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            Thread.sleep(retryDelayMillis);
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        }
        if (response.statusCode() >= 500) {
            response.body().close();
            Thread.sleep(retryDelayMillis);
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        }
        return response;
    }

    private ChatMessage handleResponse(HttpResponse<InputStream> response, Consumer<String> tokenSink) {
        if (response.statusCode() >= 500) {
            throw new ProviderException("Provider error HTTP " + response.statusCode()
                    + " after retry: " + errorBody(response));
        }
        if (response.statusCode() >= 400) {
            throw new ProviderException("Provider error HTTP " + response.statusCode()
                    + ": " + errorBody(response));
        }
        StringBuilder partial = new StringBuilder();
        Consumer<String> sink = delta -> {
            tokenSink.accept(delta);
            partial.append(delta);
        };
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
            String full = SseParser.consume(reader, sink);
            return new ChatMessage(Role.ASSISTANT, full);
        } catch (IOException e) {
            String text = partial.isEmpty() ? null : partial.toString();
            throw new ProviderException(text == null
                    ? "Network error during request: " + e.getMessage()
                    : "Stream interrupted: " + e.getMessage(), e, text);
        }
    }

    private String buildRequestBody(List<ChatMessage> history) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);
        ArrayNode messages = root.putArray("messages");
        if (config.systemPrompt() != null) {
            messages.addObject()
                    .put("role", Role.SYSTEM.apiName())
                    .put("content", config.systemPrompt());
        }
        for (ChatMessage message : history) {
            messages.addObject()
                    .put("role", message.roleName())
                    .put("content", message.content());
        }
        try {
            return JSON.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize request body", e);
        }
    }

    private HttpRequest buildRequest(String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/chat/completions"))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static String errorBody(HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(unable to read error body: " + e.getMessage() + ")";
        }
    }
}
