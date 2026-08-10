package com.mrsmith.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrsmith.io.IO;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebFetchToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static class StubIo implements IO {
        private final Deque<String> inputs = new ArrayDeque<>();
        final List<String> prompts = new ArrayList<>();

        StubIo(List<String> inputs) {
            this.inputs.addAll(inputs);
        }

        @Override
        public String readLine() {
            return inputs.isEmpty() ? null : inputs.poll();
        }

        @Override
        public void write(String text) {
        }

        @Override
        public void writeLine(String line) {
        }

        @Override
        public void writeReasoning(String text) {
        }

        @Override
        public void writeToolExecution(String line) {
        }

        @Override
        public void writePrompt(String line) {
            prompts.add(line);
        }
    }

    static final class StubResponse implements HttpResponse<InputStream> {
        private final int status;
        private final HttpHeaders headers;
        private final InputStream body;

        StubResponse(int status, HttpHeaders headers, InputStream body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public InputStream body() {
            return body;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return null;
        }

        @Override
        public HttpClient.Version version() {
            return null;
        }
    }

    static final class StubHttpClient extends HttpClient {
        private final HttpResponse<InputStream> response;

        StubHttpClient(HttpResponse<InputStream> response) {
            this.response = response;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            return null;
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Version version() {
            return null;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return (HttpResponse<T>) response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.completedFuture((HttpResponse<T>) response);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.completedFuture((HttpResponse<T>) response);
        }
    }

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private WebFetchTool tool(StubIo io) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        return new WebFetchTool(client, 5000, io);
    }

    private static ToolResult fetch(WebFetchTool tool, String url) {
        return tool.execute(JSON.createObjectNode().put("url", url));
    }

    @Test
    void classifiesPrivateAndPublicHosts() {
        assertTrue(WebFetchTool.isPrivateHost("localhost"));
        assertTrue(WebFetchTool.isPrivateHost("localhost."));
        assertTrue(WebFetchTool.isPrivateHost("foo.localhost"));
        assertTrue(WebFetchTool.isPrivateHost("foo.localhost."));
        assertTrue(WebFetchTool.isPrivateHost("127.0.0.1"));
        assertTrue(WebFetchTool.isPrivateHost("10.0.0.5"));
        assertTrue(WebFetchTool.isPrivateHost("192.168.1.1"));
        assertTrue(WebFetchTool.isPrivateHost("172.16.0.1"));
        assertTrue(WebFetchTool.isPrivateHost("169.254.169.254"));
        assertTrue(WebFetchTool.isPrivateHost("::ffff:169.254.169.254"));
        assertTrue(WebFetchTool.isPrivateHost("::1"));
        assertTrue(WebFetchTool.isPrivateHost("::"));
        assertTrue(WebFetchTool.isPrivateHost("fe80::1"));
        assertTrue(WebFetchTool.isPrivateHost("fe80::1%eth0"));
        assertTrue(WebFetchTool.isPrivateHost("fc00::1"));
        assertTrue(WebFetchTool.isPrivateHost("0.0.0.0"));
        assertTrue(WebFetchTool.isPrivateHost("2130706433"));
        assertTrue(WebFetchTool.isPrivateHost("3232235777"));
        assertFalse(WebFetchTool.isPrivateHost("example.com"));
        assertFalse(WebFetchTool.isPrivateHost("api.openai.com"));
        assertFalse(WebFetchTool.isPrivateHost("8.8.8.8"));
        assertFalse(WebFetchTool.isPrivateHost("134744072"));
    }

    @Test
    void fetchesBodyText() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("hello world"));
        WebFetchTool tool = tool(new StubIo(List.of("y")));
        ToolResult result = fetch(tool, server.url("/page").toString());
        assertFalse(result.error());
        assertTrue(result.content().contains("hello world"));
    }

    @Test
    void followsRedirects() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", "/final"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("redirected"));
        StubIo io = new StubIo(List.of("y"));
        WebFetchTool tool = tool(io);
        ToolResult result = fetch(tool, server.url("/start").toString());
        assertFalse(result.error());
        assertTrue(result.content().contains("redirected"));
        assertEquals(1, io.prompts.size());
    }

    @Test
    void returnsErrorOnHttp4xx() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("nope"));
        WebFetchTool tool = tool(new StubIo(List.of("y")));
        ToolResult result = fetch(tool, server.url("/missing").toString());
        assertTrue(result.error());
        assertTrue(result.content().contains("404"));
    }

    @Test
    void timesOutWhenServerStalls() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        WebFetchTool slowTool = new WebFetchTool(HttpClient.newHttpClient(), 300, new StubIo(List.of("y")));
        ToolResult result = fetch(slowTool, server.url("/slow").toString());
        assertTrue(result.error());
    }

    @Test
    void malformedUrlThrowsToolException() {
        WebFetchTool tool = new WebFetchTool(HttpClient.newHttpClient(), 5000, new StubIo(List.of()));
        assertThrows(ToolException.class, () -> fetch(tool, "http://"));
    }

    @Test
    void localhostDeclineReturnsNotApproved() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("secret"));
        StubIo io = new StubIo(List.of("n"));
        WebFetchTool tool = tool(io);
        ToolResult result = fetch(tool, server.url("/page").toString());
        assertTrue(result.error());
        assertTrue(result.content().contains("did not approve"));
        assertEquals(1, io.prompts.size());
    }

    @Test
    void eofAtPromptDeclinesWithoutRequest() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("secret"));
        StubIo io = new StubIo(List.of());
        WebFetchTool tool = tool(io);
        ToolResult result = fetch(tool, server.url("/page").toString());
        assertTrue(result.error());
        assertTrue(result.content().contains("did not approve"));
        assertEquals(1, io.prompts.size());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void privateHostDeclineReturnsNotApproved() {
        for (String url : List.of(
                "http://10.0.0.5/",
                "http://192.168.1.1/",
                "http://172.16.0.1/",
                "http://169.254.169.254/",
                "http://[::1]/")) {
            StubIo io = new StubIo(List.of("n"));
            WebFetchTool tool = tool(io);
            ToolResult result = fetch(tool, url);
            assertTrue(result.error(), "expected decline for " + url);
            assertTrue(result.content().contains("did not approve"), url);
            assertEquals(1, io.prompts.size(), url);
        }
    }

    @Test
    void publicHostFetchesWithoutPrompt() {
        HttpHeaders headers = HttpHeaders.of(Map.of(), (name, value) -> true);
        HttpResponse<InputStream> response = new StubResponse(200, headers,
                new ByteArrayInputStream("hello public".getBytes(StandardCharsets.UTF_8)));
        StubIo io = new StubIo(List.of());
        WebFetchTool tool = new WebFetchTool(new StubHttpClient(response), 5000, io);
        ToolResult result = fetch(tool, "http://example.com/page");
        assertFalse(result.error());
        assertTrue(result.content().contains("hello public"));
        assertTrue(io.prompts.isEmpty());
    }

    @Test
    void redirectToPrivateHostPrompts() {
        HttpHeaders headers = HttpHeaders.of(Map.of("Location", List.of("http://169.254.169.254/latest/meta-data/")),
                (name, value) -> true);
        HttpResponse<InputStream> response = new StubResponse(302, headers, InputStream.nullInputStream());
        StubIo io = new StubIo(List.of("n"));
        WebFetchTool tool = new WebFetchTool(new StubHttpClient(response), 5000, io);
        ToolResult result = fetch(tool, "http://example.com/start");
        assertTrue(result.error());
        assertTrue(result.content().contains("did not approve"));
        assertEquals(1, io.prompts.size());
    }

    @Test
    void tooManyRedirects() throws Exception {
        for (int i = 0; i < 6; i++) {
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/loop"));
        }
        WebFetchTool tool = tool(new StubIo(List.of("y")));
        ToolResult result = fetch(tool, server.url("/loop").toString());
        assertTrue(result.error());
        assertTrue(result.content().contains("too many redirects"));
    }

    @Test
    void redirectToNonHttpSchemeIsRejected() {
        HttpHeaders headers = HttpHeaders.of(Map.of("Location", List.of("ftp://example.com/file")),
                (name, value) -> true);
        HttpResponse<InputStream> response = new StubResponse(302, headers, InputStream.nullInputStream());
        WebFetchTool tool = new WebFetchTool(new StubHttpClient(response), 5000, new StubIo(List.of()));
        ToolResult result = fetch(tool, "http://example.com/start");
        assertTrue(result.error());
        assertTrue(result.content().contains("invalid redirect location"));
    }
}
