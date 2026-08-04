package com.mrsmith.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebFetchToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MockWebServer server;
    private WebFetchTool tool;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        tool = new WebFetchTool(client, 5000);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void fetchesBodyText() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("hello world"));
        ToolResult result = tool.execute(JSON.readTree("{\"url\":\"" + server.url("/page") + "\"}"));
        assertFalse(result.error());
        assertTrue(result.content().contains("hello world"));
    }

    @Test
    void followsRedirects() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", "/final"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("redirected"));
        ToolResult result = tool.execute(JSON.readTree("{\"url\":\"" + server.url("/start") + "\"}"));
        assertFalse(result.error());
        assertTrue(result.content().contains("redirected"));
    }

    @Test
    void returnsErrorOnHttp4xx() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("nope"));
        ToolResult result = tool.execute(JSON.readTree("{\"url\":\"" + server.url("/missing") + "\"}"));
        assertTrue(result.error());
        assertTrue(result.content().contains("404"));
    }

    @Test
    void timesOutWhenServerStalls() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        WebFetchTool slowTool = new WebFetchTool(HttpClient.newHttpClient(), 300);
        ToolResult result = slowTool.execute(JSON.readTree("{\"url\":\"" + server.url("/slow") + "\"}"));
        assertTrue(result.error());
    }
}
