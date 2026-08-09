# Web Fetch SSRF Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent `web_fetch` from silently fetching private/link-local/localhost hosts. When such a host is detected, ask the user for approval inline; on decline, return a tool result telling the model it was not approved. Redirects are followed manually with every hop re-checked.

**Architecture:** `WebFetchTool` gains an `IO` dependency and a `isPrivateHost(String)` classifier (localhost / `*.localhost` / IP-literal ranges via `InetAddress` predicates; no DNS for plain hostnames). The tool's `execute` runs a recursive `fetch(uri, approvedHosts, redirects)` that prompts `[y/N]` when a not-yet-approved private host is targeted, caches approvals per host per call, and follows 3xx `Location` hops manually (max 5) re-running the check on each. `ToolRegistry.BUILT_INS` becomes `Map<String, Function<IO, Tool>>` so `web_fetch` is built with the session's `IO`. Spec: `docs/superpowers/specs/2026-08-09-web-fetch-ssrf-hardening-design.md`.

**Tech Stack:** Java 21, JUnit 5 (Jupiter), OkHttp MockWebServer (tests only), Maven.

---

## File Structure

**Modify (main):**
- `src/main/java/com/mrsmith/tool/WebFetchTool.java` — IO constructors, `isPrivateHost`, approval prompt, manual redirect following
- `src/main/java/com/mrsmith/tool/ToolRegistry.java` — `BUILT_INS` value type `Supplier<Tool>` → `Function<IO, Tool>`

**Modify (test):**
- `src/test/java/com/mrsmith/tool/WebFetchToolTest.java` — full rewrite: `StubIo` (records prompts), stub `HttpClient`/`HttpResponse` for public/redirect tests, existing behavior tests updated for the IO constructor, new SSRF tests

**Modify (docs):**
- `README.md` — `web_fetch` row in the tools table

---

### Task 1: Rewrite WebFetchToolTest with SSRF tests

**Files:**
- Modify: `src/test/java/com/mrsmith/tool/WebFetchToolTest.java`

- [ ] **Step 1: Replace the test file entirely**

Replace the full content of `src/test/java/com/mrsmith/tool/WebFetchToolTest.java` with:

```java
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
        public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> responseBodyHandler) {
            return (HttpResponse<T>) response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.completedFuture((HttpResponse<T>) response);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> responseBodyHandler,
                                                                PushPromiseHandler<T> pushPromiseHandler) {
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
        return tool.execute(JSON.readTree("{\"url\":\"" + url + "\"}"));
    }

    @Test
    void classifiesPrivateAndPublicHosts() {
        assertTrue(WebFetchTool.isPrivateHost("localhost"));
        assertTrue(WebFetchTool.isPrivateHost("127.0.0.1"));
        assertTrue(WebFetchTool.isPrivateHost("10.0.0.5"));
        assertTrue(WebFetchTool.isPrivateHost("192.168.1.1"));
        assertTrue(WebFetchTool.isPrivateHost("172.16.0.1"));
        assertTrue(WebFetchTool.isPrivateHost("169.254.169.254"));
        assertTrue(WebFetchTool.isPrivateHost("::1"));
        assertTrue(WebFetchTool.isPrivateHost("0.0.0.0"));
        assertFalse(WebFetchTool.isPrivateHost("example.com"));
        assertFalse(WebFetchTool.isPrivateHost("api.openai.com"));
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
}
```

Notes on behavior:
- `MockWebServer` binds to `localhost`, so every fetch test URL is a private host and the stub must supply `"y"` (approve) to exercise the fetch path.
- `tooManyRedirects` enqueues 6 `302`s: the initial request plus 5 follows; `handleRedirect` stops when `redirects >= 5`.
- `redirectToPrivateHostPrompts` uses a stub `HttpClient` that returns one canned `302`; the follow-up to `169.254.169.254` is declined before any request is sent, so the stub is only hit once.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -Dtest=WebFetchToolTest test`
Expected: FAIL — compilation errors: `WebFetchTool(IO)`, `WebFetchTool(HttpClient, long, IO)` and `WebFetchTool.isPrivateHost` do not exist yet (current constructors are `WebFetchTool()` and `WebFetchTool(HttpClient, long)`).

- [ ] **Step 3: Commit the failing test**

```bash
git add src/test/java/com/mrsmith/tool/WebFetchToolTest.java
git commit -m "test: SSRF tests for web_fetch private-host approval"
```

---

### Task 2: Implement WebFetchTool SSRF + rewire ToolRegistry

`WebFetchTool` and `ToolRegistry` must change together: after `WebFetchTool` drops its no-arg constructor, `ToolRegistry`'s `WebFetchTool::new` no longer compiles as a `Supplier<Tool>`. They are updated in one task to keep the module compiling.

**Files:**
- Modify: `src/main/java/com/mrsmith/tool/WebFetchTool.java`
- Modify: `src/main/java/com/mrsmith/tool/ToolRegistry.java`

- [ ] **Step 1: Replace WebFetchTool.java entirely**

Replace the full content of `src/main/java/com/mrsmith/tool/WebFetchTool.java` with:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.io.IO;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class WebFetchTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_BYTES = 1_048_576;
    private static final int MAX_REDIRECTS = 5;

    private final HttpClient httpClient;
    private final long timeoutMillis;
    private final IO io;

    public WebFetchTool(IO io) {
        this(HttpClient.newHttpClient(), 10_000L, io);
    }

    public WebFetchTool(HttpClient httpClient, long timeoutMillis, IO io) {
        this.httpClient = httpClient;
        this.timeoutMillis = timeoutMillis;
        this.io = io;
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
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ToolException("invalid url: " + url, e);
        }
        if (uri.getHost() == null) {
            throw new ToolException("invalid url: " + url);
        }
        return fetch(uri, new HashSet<>(), 0);
    }

    private ToolResult fetch(URI uri, Set<String> approvedHosts, int redirects) {
        String host = uri.getHost();
        if (isPrivateHost(host) && !approvedHosts.contains(host) && !userApproves(uri)) {
            return new ToolResult("User did not approve fetching " + uri
                    + " (private/link-local/localhost host).", true);
        }
        approvedHosts.add(host);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("User-Agent", "mr-smith")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw new ToolException("invalid url: " + uri, e);
        }
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                return handleRedirect(response, uri, approvedHosts, redirects);
            }
            if (response.statusCode() >= 400) {
                return new ToolResult("HTTP " + response.statusCode(), true);
            }
            try (InputStream body = response.body()) {
                byte[] bytes = body.readNBytes((int) MAX_BYTES + 1);
                boolean truncated = bytes.length > MAX_BYTES;
                if (truncated) {
                    bytes = Arrays.copyOf(bytes, (int) MAX_BYTES);
                }
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (truncated) {
                    text = text + "\n[truncated]";
                }
                return new ToolResult(text, false);
            }
        } catch (IOException e) {
            return new ToolResult("fetch failed: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("fetch interrupted");
        }
    }

    private ToolResult handleRedirect(HttpResponse<InputStream> response, URI current,
                                      Set<String> approvedHosts, int redirects) {
        if (redirects >= MAX_REDIRECTS) {
            close(response);
            return new ToolResult("too many redirects", true);
        }
        String location = response.headers().firstValue("Location").orElse(null);
        close(response);
        if (location == null) {
            return new ToolResult("HTTP " + response.statusCode(), true);
        }
        URI next;
        try {
            next = current.resolve(location);
        } catch (IllegalArgumentException e) {
            return new ToolResult("invalid redirect location: " + location, true);
        }
        if (next.getHost() == null) {
            return new ToolResult("invalid redirect location: " + location, true);
        }
        return fetch(next, approvedHosts, redirects + 1);
    }

    private static void close(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
        }
    }

    private boolean userApproves(URI uri) {
        io.writePrompt("web_fetch: " + uri + " targets a private/link-local/localhost host. Fetch anyway? [y/N] ");
        String answer;
        try {
            answer = io.readLine();
        } catch (IOException e) {
            return false;
        }
        return answer != null && (answer.trim().equalsIgnoreCase("y")
                || answer.trim().equalsIgnoreCase("yes"));
    }

    static boolean isPrivateHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost")) {
            return true;
        }
        if (!isIpLiteral(h)) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(h);
            return address.isLoopbackAddress() || address.isAnyLocalAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        return host.matches("\\d+(\\.\\d+){3}");
    }
}
```

- [ ] **Step 2: Update ToolRegistry.java**

In `src/main/java/com/mrsmith/tool/ToolRegistry.java`:

1. Change the import `import java.util.function.Supplier;` to:

```java
import java.util.function.Function;
```

2. Replace the `BUILT_INS` declaration and static block:

```java
    private static final Map<String, Function<IO, Tool>> BUILT_INS = new LinkedHashMap<>();

    static {
        BUILT_INS.put("shell", io -> new ShellTool());
        BUILT_INS.put("read_file", io -> new ReadFileTool());
        BUILT_INS.put("write_file", io -> new WriteFileTool());
        BUILT_INS.put("list_dir", io -> new ListDirTool());
        BUILT_INS.put("glob", io -> new GlobTool());
        BUILT_INS.put("web_fetch", WebFetchTool::new);
    }
```

3. Replace the lookup loop in `ToolRegistry.with(...)`:

```java
            Function<IO, Tool> factory = BUILT_INS.get(name);
            if (factory == null) {
                throw new ToolException("Unknown tool: " + name);
            }
            tools.add(factory.apply(io));
```

`builtinNames()` is unchanged, so `AgentCatalog` tool-name validation still accepts `web_fetch`.

- [ ] **Step 3: Run the tests to verify they pass**

Run: `mvn -q -Dtest=WebFetchToolTest,ToolRegistryTest test`
Expected: PASS — `WebFetchToolTest` now has 11 tests (5 behavior + 6 new), all green; `ToolRegistryTest` unchanged and green.

- [ ] **Step 4: Run the full suite**

Run: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: BUILD SUCCESS — 297 tests (was 291; WebFetchToolTest went from 5 to 11).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/tool/WebFetchTool.java src/main/java/com/mrsmith/tool/ToolRegistry.java
git commit -m "feat: harden web_fetch against private/link-local/localhost fetches with user approval"
```

---

### Task 3: Update README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the web_fetch tools-table row**

In `README.md`, replace the `web_fetch` row in the "Per-agent built-ins" table:

```markdown
| `web_fetch` | yes | Fetches an HTTP(S) URL and returns the body text (1 MiB cap, follows redirects) |
```

with:

```markdown
| `web_fetch` | yes | Fetches an HTTP(S) URL and returns the body text (1 MiB cap); asks you before fetching private/link-local/localhost hosts, and re-checks every redirect hop |
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: describe web_fetch private-host approval"
```
