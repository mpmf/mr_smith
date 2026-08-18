package com.mrsmith.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.config.AgentRuntime;
import com.mrsmith.tool.Tool;
import com.mrsmith.util.Json;

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

    private static final ObjectMapper JSON = Json.MAPPER;

    private final AgentRuntime runtime;
    private final HttpClient httpClient;
    private final long retryDelayMillis;

    public OpenAiCompatibleProvider(AgentRuntime runtime) {
        this(runtime, HttpClient.newHttpClient(), 2000L);
    }

    OpenAiCompatibleProvider(AgentRuntime runtime, HttpClient httpClient) {
        this(runtime, httpClient, 2000L);
    }

    OpenAiCompatibleProvider(AgentRuntime runtime, HttpClient httpClient, long retryDelayMillis) {
        this.runtime = runtime;
        this.httpClient = httpClient;
        this.retryDelayMillis = retryDelayMillis;
    }

    @Override
    public ProviderResponse send(List<ChatMessage> context, List<Tool> tools,
                                 Consumer<String> tokenSink, Consumer<String> reasoningSink) {
        try {
            return doSend(context, tools, tokenSink, reasoningSink);
        } catch (ProviderException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Network error while contacting "
                    + runtime.provider().baseUrl() + ": " + e.getMessage(), e);
        }
    }

    private ProviderResponse doSend(List<ChatMessage> context, List<Tool> tools,
                                    Consumer<String> tokenSink, Consumer<String> reasoningSink)
            throws IOException, InterruptedException {
        HttpRequest request = buildRequest(buildRequestBody(context, tools));
        HttpResponse<InputStream> response = sendWithRetry(request);
        return handleResponse(response, context, tokenSink, reasoningSink);
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

    private ProviderResponse handleResponse(HttpResponse<InputStream> response, List<ChatMessage> context,
                                            Consumer<String> tokenSink, Consumer<String> reasoningSink) {
        if (response.statusCode() >= 500) {
            throw new ProviderException("Provider error HTTP " + response.statusCode()
                    + " after retry: " + errorBody(response));
        }
        if (response.statusCode() >= 400) {
            throw new ProviderException("Provider error HTTP " + response.statusCode()
                    + ": " + errorBody(response));
        }
        StringBuilder partial = new StringBuilder();
        StringBuilder partialThinking = new StringBuilder();
        Consumer<String> sink = delta -> {
            tokenSink.accept(delta);
            partial.append(delta);
        };
        Consumer<String> reasoning = delta -> {
            reasoningSink.accept(delta);
            partialThinking.append(delta);
        };
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
            SseResult result = SseParser.consume(reader, sink, reasoning);
            ChatMessage message = new ChatMessage(Role.ASSISTANT, result.content(), result.thinking(),
                    result.toolCalls(), null);
            Usage usage = result.usage();
            boolean estimated = false;
            if (usage == null) {
                usage = estimateUsage(context, result.content(), result.thinking());
                estimated = true;
            }
            return new ProviderResponse(message, usage, estimated);
        } catch (IOException e) {
            String text = partial.isEmpty() ? null : partial.toString();
            String thinking = partialThinking.isEmpty() ? null : partialThinking.toString();
            throw new ProviderException(text == null && thinking == null
                    ? "Network error during request: " + e.getMessage()
                    : "Stream interrupted: " + e.getMessage(), e, text, thinking);
        }
    }

    private Usage estimateUsage(List<ChatMessage> context, String replyContent, String thinking) {
        int prompt = 0;
        for (ChatMessage message : context) {
            prompt += TokenEstimator.estimateMessageTokens(message);
        }
        int completion = TokenEstimator.estimateTokens(replyContent);
        if (thinking != null) {
            completion += TokenEstimator.estimateTokens(thinking);
        }
        return new Usage(prompt, completion);
    }

    private String buildRequestBody(List<ChatMessage> context, List<Tool> tools) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", runtime.agent().model());
        root.put("stream", true);
        String effort = runtime.agent().reasoningEffort();
        if (effort != null && !effort.isBlank()) {
            root.put("reasoning_effort", effort);
        }
        if (runtime.globals().includeUsage()) {
            root.putObject("stream_options").put("include_usage", true);
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = root.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode entry = toolsArray.addObject();
                entry.put("type", "function");
                ObjectNode fn = entry.putObject("function");
                fn.put("name", tool.name());
                fn.put("description", tool.description());
                fn.set("parameters", tool.parametersSchema());
            }
        }
        ArrayNode messages = root.putArray("messages");
        for (ChatMessage message : context) {
            messages.add(serializeMessage(message));
        }
        try {
            return JSON.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize request body", e);
        }
    }

    private ObjectNode serializeMessage(ChatMessage message) {
        ObjectNode node = JSON.createObjectNode();
        node.put("role", message.roleName());
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            node.putNull("content");
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolCall call : message.toolCalls()) {
                ObjectNode entry = calls.addObject();
                entry.put("id", call.id());
                entry.put("type", "function");
                ObjectNode fn = entry.putObject("function");
                fn.put("name", call.name());
                fn.put("arguments", call.arguments() == null ? "{}" : call.arguments().toString());
            }
            return node;
        }
        if (message.role() == Role.TOOL) {
            if (message.toolCallId() == null) {
                throw new IllegalArgumentException("Tool result message is missing a tool_call_id");
            }
            node.put("tool_call_id", message.toolCallId());
            node.put("content", message.content() == null ? "" : message.content());
            return node;
        }
        String content = message.content() == null ? "" : message.content();
        node.put("content", content);
        return node;
    }

    private HttpRequest buildRequest(String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(runtime.provider().baseUrl() + "/chat/completions"))
                .header("Authorization", "Bearer " + runtime.provider().apiKey())
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
