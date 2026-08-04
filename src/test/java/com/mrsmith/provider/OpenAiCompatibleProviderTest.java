package com.mrsmith.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrsmith.config.AppConfig;
import com.mrsmith.tool.Tool;
import com.mrsmith.tool.ToolResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleProviderTest {

    private MockWebServer server;
    private OpenAiCompatibleProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        AppConfig config = new AppConfig("sk-test", server.url("/").toString(), "test-model", null);
        provider = new OpenAiCompatibleProvider(config, HttpClient.newHttpClient(), 0L);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void streamsTokensAndReturnsFullMessage() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"delta":{"content":"Hi"}}]}

                        data: {"choices":[{"delta":{"content":" there"}}]}

                        data: [DONE]

                        """));
        List<String> deltas = new ArrayList<>();
        ProviderResponse response = provider.send(List.of(new ChatMessage(Role.USER, "hello")), List.of(), deltas::add, s -> { });
        assertEquals("Hi there", response.message().content());
        assertEquals(List.of("Hi", " there"), deltas);
        assertNotNull(response.usage());
        assertTrue(response.usageEstimated());
    }

    @Test
    void sendsCorrectRequestBodyAndAuth() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        provider.send(List.of(new ChatMessage(Role.USER, "hello")), List.of(), s -> { }, s -> { });
        RecordedRequest request = server.takeRequest();
        assertEquals("Bearer sk-test", request.getHeader("Authorization"));
        assertEquals("/chat/completions", request.getPath());
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"model\":\"test-model\""));
        assertTrue(body.contains("\"stream\":true"));
        assertTrue(body.contains("\"stream_options\":{\"include_usage\":true}"));
        assertTrue(body.contains("\"role\":\"user\""));
        assertTrue(body.contains("\"content\":\"hello\""));
    }

    @Test
    void serializesSystemMessageVerbatim() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        List<ChatMessage> context = List.of(
                new ChatMessage(Role.SYSTEM, "You are helpful"),
                new ChatMessage(Role.USER, "hello"));
        provider.send(context, List.of(), s -> { }, s -> { });
        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"role\":\"system\""));
        assertTrue(body.contains("\"content\":\"You are helpful\""));
    }

    @Test
    void throwsOn4xx() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"invalid key\"}"));
        ProviderException e = assertThrows(ProviderException.class,
                () -> provider.send(List.of(new ChatMessage(Role.USER, "hi")), List.of(), s -> { }, s -> { }));
        assertTrue(e.getMessage().contains("401"));
    }

    @Test
    void retriesOnceOn5xxThenSucceeds() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"));
        List<String> deltas = new ArrayList<>();
        ProviderResponse response = provider.send(List.of(new ChatMessage(Role.USER, "hi")), List.of(), deltas::add, s -> { });
        assertEquals("ok", response.message().content());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void givesUpAfterRetryOnPersistent5xx() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        server.enqueue(new MockResponse().setResponseCode(502).setBody("boom2"));
        ProviderException e = assertThrows(ProviderException.class,
                () -> provider.send(List.of(new ChatMessage(Role.USER, "hi")), List.of(), s -> { }, s -> { }));
        assertTrue(e.getMessage().contains("502"));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void preservesPartialContentWhenStreamIsInterrupted() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                .setBody("""
                        data: {"choices":[{"delta":{"content":"par"}}]}

                        data: {"choices":[{"delta":{"content":"tial"}}]}

                        data: [DONE]

                        """));
        List<String> deltas = new ArrayList<>();
        ProviderException e = assertThrows(ProviderException.class,
                () -> provider.send(List.of(new ChatMessage(Role.USER, "hi")), List.of(), deltas::add, s -> { }));
        assertTrue(e.hasPartialContent());
        assertEquals(String.join("", deltas), e.partialContent());
        assertTrue(e.getMessage().contains("interrupted"));
    }

    @Test
    void preservesPartialThinkingWhenStreamIsInterrupted() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                .setBody("""
                        data: {"choices":[{"delta":{"reasoning_content":"half "}}]}

                        data: {"choices":[{"delta":{"reasoning_content":"done"}}]}

                        data: {"choices":[{"delta":{"content":"answer"}}]}

                        data: [DONE]

                        """));
        List<String> reasoning = new ArrayList<>();
        ProviderException e = assertThrows(ProviderException.class,
                () -> provider.send(List.of(new ChatMessage(Role.USER, "hi")), List.of(), s -> { }, reasoning::add));
        assertNotNull(e.partialThinking());
        assertEquals(String.join("", reasoning), e.partialThinking());
    }

    @Test
    void retriesOnNetworkFailureThenSucceeds() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"));
        List<String> deltas = new ArrayList<>();
        ProviderResponse response = provider.send(List.of(new ChatMessage(Role.USER, "hi")), List.of(), deltas::add, s -> { });
        assertEquals("ok", response.message().content());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void usesRealUsageWhenProviderSendsIt() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"delta":{"content":"ok"}}]}

                        data: {"usage":{"prompt_tokens":1200,"completion_tokens":300}}

                        data: [DONE]

                        """));
        ProviderResponse response = provider.send(List.of(new ChatMessage(Role.USER, "hi")), List.of(), s -> { }, s -> { });
        assertEquals("ok", response.message().content());
        assertEquals(new Usage(1200, 300), response.usage());
        assertFalse(response.usageEstimated());
    }

    @Test
    void estimatesUsageWhenProviderSendsNone() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"));
        ProviderResponse response = provider.send(List.of(new ChatMessage(Role.USER, "hi")), List.of(), s -> { }, s -> { });
        assertTrue(response.usageEstimated());
        assertEquals(1, response.usage().promptTokens());
        assertEquals(1, response.usage().completionTokens());
    }

    @Test
    void includeUsageDisabledOmitsStreamOptions() throws Exception {
        server.shutdown();
        server = new MockWebServer();
        server.start();
        AppConfig config = new AppConfig("sk-test", server.url("/").toString(), "test-model", null, null, false);
        provider = new OpenAiCompatibleProvider(config, HttpClient.newHttpClient(), 0L);
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        provider.send(List.of(new ChatMessage(Role.USER, "hello")), List.of(), s -> { }, s -> { });
        RecordedRequest request = server.takeRequest();
        assertFalse(request.getBody().readUtf8().contains("stream_options"));
    }

    @Test
    void streamsReasoningToSink() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"delta":{"reasoning_content":"think"}}]}

                        data: {"choices":[{"delta":{"content":"ok"}}]}

                        data: [DONE]

                        """));
        List<String> content = new ArrayList<>();
        List<String> reasoning = new ArrayList<>();
        ProviderResponse response = provider.send(List.of(new ChatMessage(Role.USER, "hi")), List.of(), content::add, reasoning::add);
        assertEquals(List.of("think"), reasoning);
        assertEquals("ok", response.message().content());
        assertEquals("think", response.message().thinking());
    }

    @Test
    void reasoningNotSentBackInHistory() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"secret thinking\",\"content\":\"hello\"}}]}\n\ndata: [DONE]\n\n"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage(Role.USER, "first"));
        ProviderResponse first = provider.send(history, List.of(), s -> { }, s -> { });
        assertEquals("secret thinking", first.message().thinking());
        RecordedRequest firstRequest = server.takeRequest();
        String firstBody = firstRequest.getBody().readUtf8();
        assertTrue(firstBody.contains("\"content\":\"first\""));
        assertFalse(firstBody.contains("secret thinking"));
        history.add(first.message());
        history.add(new ChatMessage(Role.USER, "second"));
        provider.send(history, List.of(), s -> { }, s -> { });
        RecordedRequest secondRequest = server.takeRequest();
        String secondBody = secondRequest.getBody().readUtf8();
        assertTrue(secondBody.contains("\"content\":\"hello\""));
        assertFalse(secondBody.contains("secret thinking"));
    }

    @Test
    void estimateCountsThinkingTokens() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"delta":{"reasoning_content":"think"}}]}

                        data: {"choices":[{"delta":{"content":"ok"}}]}

                        data: [DONE]

                        """));
        ProviderResponse response = provider.send(List.of(new ChatMessage(Role.USER, "hi")), List.of(), s -> { }, s -> { });
        assertTrue(response.usageEstimated());
        assertEquals(3, response.usage().completionTokens());
    }

    @Test
    void coercesNullContentToEmptyString() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        List<ChatMessage> context = List.of(new ChatMessage(Role.ASSISTANT, null, "think"));
        provider.send(context, List.of(), s -> { }, s -> { });
        RecordedRequest request = server.takeRequest();
        assertTrue(request.getBody().readUtf8().contains("\"content\":\"\""));
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    static class StubTool implements Tool {
        private final String name;
        private final JsonNode schema;

        StubTool(String name) {
            this(name, JSON.createObjectNode());
        }

        StubTool(String name, JsonNode schema) {
            this.name = name;
            this.schema = schema;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name + " description";
        }

        @Override
        public JsonNode parametersSchema() {
            return schema;
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public ToolResult execute(JsonNode args) {
            return new ToolResult("ok", false);
        }
    }

    @Test
    void includesToolsArrayInRequest() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        JsonNode schema = JSON.readTree("{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}}}");
        provider.send(List.of(new ChatMessage(Role.USER, "hi")), List.of(new StubTool("shell", schema)),
                s -> { }, s -> { });
        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"tools\":["));
        assertTrue(body.contains("\"type\":\"function\""));
        assertTrue(body.contains("\"name\":\"shell\""));
        assertTrue(body.contains("\"description\":\"shell description\""));
        assertTrue(body.contains("\"parameters\":{\"type\":\"object\""));
    }

    @Test
    void omitsToolsArrayWhenEmpty() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        provider.send(List.of(new ChatMessage(Role.USER, "hi")), List.of(), s -> { }, s -> { });
        RecordedRequest request = server.takeRequest();
        assertFalse(request.getBody().readUtf8().contains("\"tools\""));
    }

    @Test
    void serializesAssistantToolCallsMessage() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        JsonNode args = JSON.readTree("{\"command\":\"ls\"}");
        ChatMessage assistant = new ChatMessage(Role.ASSISTANT, null, null,
                List.of(new ToolCall("call_1", "shell", args)), null);
        provider.send(List.of(assistant), List.of(), s -> { }, s -> { });
        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"tool_calls\":[{"));
        assertTrue(body.contains("\"id\":\"call_1\""));
        assertTrue(body.contains("\"function\":{\"name\":\"shell\",\"arguments\":\"{\\\"command\\\":\\\"ls\\\"}\"}"));
    }

    @Test
    void serializesToolResultMessage() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        ChatMessage toolResult = new ChatMessage(Role.TOOL, "42", null, null, "call_1");
        provider.send(List.of(toolResult), List.of(), s -> { }, s -> { });
        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"role\":\"tool\""));
        assertTrue(body.contains("\"tool_call_id\":\"call_1\""));
        assertTrue(body.contains("\"content\":\"42\""));
    }

    @Test
    void surfacesToolCallsFromSseResponse() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_9","function":{"name":"shell","arguments":"{\\"command\\":\\"ls\\"}"}}]}}]}

                        data: [DONE]

                        """));
        ProviderResponse response = provider.send(List.of(new ChatMessage(Role.USER, "hi")), List.of(),
                s -> { }, s -> { });
        assertEquals(1, response.message().toolCalls().size());
        assertEquals("call_9", response.message().toolCalls().get(0).id());
        assertEquals("shell", response.message().toolCalls().get(0).name());
    }
}
