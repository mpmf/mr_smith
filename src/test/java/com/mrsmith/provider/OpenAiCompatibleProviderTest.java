package com.mrsmith.provider;

import com.mrsmith.config.AppConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        ChatMessage reply = provider.send(List.of(new ChatMessage(Role.USER, "hello")), deltas::add);
        assertEquals("Hi there", reply.content());
        assertEquals(List.of("Hi", " there"), deltas);
    }

    @Test
    void sendsCorrectRequestBodyAndAuth() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        provider.send(List.of(new ChatMessage(Role.USER, "hello")), s -> { });
        RecordedRequest request = server.takeRequest();
        assertEquals("Bearer sk-test", request.getHeader("Authorization"));
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"model\":\"test-model\""));
        assertTrue(body.contains("\"stream\":true"));
        assertTrue(body.contains("\"role\":\"user\""));
        assertTrue(body.contains("\"content\":\"hello\""));
    }

    @Test
    void includesSystemPromptWhenConfigured() throws Exception {
        AppConfig config = new AppConfig("sk-test", server.url("/").toString(), "test-model", "You are helpful");
        provider = new OpenAiCompatibleProvider(config, HttpClient.newHttpClient(), 0L);
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        provider.send(List.of(new ChatMessage(Role.USER, "hello")), s -> { });
        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"role\":\"system\""));
        assertTrue(body.contains("\"content\":\"You are helpful\""));
    }

    @Test
    void throwsOn4xx() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"invalid key\"}"));
        ProviderException e = assertThrows(ProviderException.class,
                () -> provider.send(List.of(new ChatMessage(Role.USER, "hi")), s -> { }));
        assertTrue(e.getMessage().contains("401"));
    }

    @Test
    void retriesOnceOn5xxThenSucceeds() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"));
        List<String> deltas = new ArrayList<>();
        ChatMessage reply = provider.send(List.of(new ChatMessage(Role.USER, "hi")), deltas::add);
        assertEquals("ok", reply.content());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void givesUpAfterRetryOnPersistent5xx() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        server.enqueue(new MockResponse().setResponseCode(502).setBody("boom2"));
        ProviderException e = assertThrows(ProviderException.class,
                () -> provider.send(List.of(new ChatMessage(Role.USER, "hi")), s -> { }));
        assertTrue(e.getMessage().contains("502"));
        assertEquals(2, server.getRequestCount());
    }
}
