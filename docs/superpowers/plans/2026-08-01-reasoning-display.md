# Mr Smith — Reasoning Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show reasoning-model "thinking" text: extract it from the stream (`reasoning_content` with `reasoning` fallback), stream it live in yellow through a new `IO` capability, and store it on the assistant message in a `thinking` field that is never sent back to the model.

**Architecture:** `SseParser.consume` gains a reasoning sink and returns `SseResult(content, thinking, usage)`. `Provider.send` gains a `reasoningSink` parameter so the thinking streams live to the IO layer while also being accumulated onto the assistant `ChatMessage` via a new nullable `thinking` field. `IO` gains `writeReasoning(String)`; `ReplIo` renders it in ANSI yellow only when stdout is a TTY. Request serialization writes only `role` + `content`, so thinking stays in local history and never re-sent. Interrupted reasoning is preserved via a new `partialThinking` on `ProviderException`.

**Tech Stack:** Java 21 · Maven · JUnit 5 · MockWebServer (tests only). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-01-reasoning-display-design.md`

## File Structure

All paths relative to repo root `/Users/marcoferreira/Projects/mr_smith`.

**Modified production sources** (`src/main/java/com/mrsmith/`):

| File | Change |
|---|---|
| `provider/ChatMessage.java` | Add nullable `String thinking` + 2-arg convenience ctor |
| `provider/ProviderException.java` | Add nullable `partialThinking` + 4-arg ctor |
| `provider/SseResult.java` | Becomes `(String content, String thinking, Usage usage)` |
| `provider/SseParser.java` | `consume(reader, contentSink, reasoningSink)`; extract reasoning |
| `provider/Provider.java` | `send` gains `Consumer<String> reasoningSink` |
| `provider/OpenAiCompatibleProvider.java` | Build message with thinking; stream reasoning; estimate counts thinking; preserve partial thinking |
| `io/IO.java` | Add `void writeReasoning(String text)` |
| `io/ReplIo.java` | ANSI-yellow reasoning; TTY detection |
| `chat/ChatSession.java` | Pass `io::writeReasoning`; store thinking; recover partial thinking |

**Tests** (new): `provider/ChatMessageTest`.
**Tests** (modified): `provider/SseParserTest`, `io/ReplIoTest`, `provider/ProviderExceptionTest`, `provider/OpenAiCompatibleProviderTest`, `chat/ChatSessionTest` (fakes + new tests).

## Build & Test Commands

- Compile: `mvn -q compile`
- Test: `mvn -q test`
- Single test class: `mvn -q test -Dtest=ClassName`
- Package: `mvn -q package` → `target/mr-smith.jar`

Current baseline: 68 tests, all green on `master`.

---

### Task 1: ChatMessage thinking Field

**Files:**
- Modify: `src/main/java/com/mrsmith/provider/ChatMessage.java`
- Create: `src/test/java/com/mrsmith/provider/ChatMessageTest.java`

- [ ] **Step 1: Write the failing test** — create `src/test/java/com/mrsmith/provider/ChatMessageTest.java`:

```java
package com.mrsmith.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatMessageTest {

    @Test
    void twoArgConstructorDefaultsThinkingToNull() {
        ChatMessage message = new ChatMessage(Role.USER, "hello");
        assertEquals("hello", message.content());
        assertNull(message.thinking());
    }

    @Test
    void threeArgConstructorStoresThinking() {
        ChatMessage message = new ChatMessage(Role.ASSISTANT, "answer", "think step by step");
        assertEquals("answer", message.content());
        assertEquals("think step by step", message.thinking());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ChatMessageTest`
Expected: FAIL — compilation error, `thinking()` / 3-arg constructor not defined.

- [ ] **Step 3: Modify `ChatMessage.java`** — replace the ENTIRE file:

```java
package com.mrsmith.provider;

public record ChatMessage(Role role, String content, String thinking) {

    public ChatMessage(Role role, String content) {
        this(role, content, null);
    }

    public String roleName() {
        return role.apiName();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=ChatMessageTest`
Expected: PASS, `BUILD SUCCESS`. The rest of the suite still compiles (2-arg constructor preserved).

- [ ] **Step 5: Run the full suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` — 70 tests green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/provider/ChatMessage.java src/test/java/com/mrsmith/provider/ChatMessageTest.java
git commit -m "feat: add thinking field to ChatMessage"
```

---

### Task 2: IO writeReasoning and ReplIo Yellow

**Files:**
- Modify: `src/main/java/com/mrsmith/io/IO.java`
- Modify: `src/main/java/com/mrsmith/io/ReplIo.java`
- Modify: `src/test/java/com/mrsmith/io/ReplIoTest.java`
- Modify: `src/test/java/com/mrsmith/chat/ChatSessionTest.java` (add `writeReasoning` to `StubIo`)

- [ ] **Step 1: Write the failing tests — replace `ReplIoTest.java` content entirely**

```java
package com.mrsmith.io;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplIoTest {

    private static final String YELLOW = "\u001B[33m";
    private static final String RESET = "\u001B[0m";

    @Test
    void readsLinesFromReader() throws IOException {
        ReplIo io = new ReplIo(new BufferedReader(new StringReader("hello\n/exit\n")), new PrintStream(new ByteArrayOutputStream()), false);
        assertEquals("hello", io.readLine());
        assertEquals("/exit", io.readLine());
    }

    @Test
    void writeAppendsWithoutNewline() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ReplIo io = new ReplIo(new BufferedReader(new StringReader("")), new PrintStream(new BufferedOutputStream(buffer)), false);
        io.write("a");
        io.write("b");
        assertEquals("ab", buffer.toString());
    }

    @Test
    void writeLineAppendsNewline() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ReplIo io = new ReplIo(new BufferedReader(new StringReader("")), new PrintStream(buffer), false);
        io.writeLine("hi");
        assertEquals("hi" + System.lineSeparator(), buffer.toString());
    }

    @Test
    void writeReasoningWrapsInYellowWhenColorEnabled() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ReplIo io = new ReplIo(new BufferedReader(new StringReader("")), new PrintStream(buffer), true);
        io.writeReasoning("think");
        assertEquals(YELLOW + "think" + RESET, buffer.toString());
    }

    @Test
    void writeReasoningIsPlainWhenColorDisabled() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ReplIo io = new ReplIo(new BufferedReader(new StringReader("")), new PrintStream(buffer), false);
        io.writeReasoning("think");
        assertEquals("think", buffer.toString());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ReplIoTest`
Expected: FAIL — compilation errors: `writeReasoning` not defined, 3-arg constructor mismatch.

- [ ] **Step 3: Modify `IO.java`** — replace the ENTIRE file:

```java
package com.mrsmith.io;

import java.io.IOException;

public interface IO {

    String readLine() throws IOException;

    void write(String text);

    void writeLine(String line);

    void writeReasoning(String text);
}
```

- [ ] **Step 4: Modify `ReplIo.java`** — replace the ENTIRE file:

```java
package com.mrsmith.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;

public class ReplIo implements IO {

    private static final String YELLOW = "\u001B[33m";
    private static final String RESET = "\u001B[0m";

    private final BufferedReader reader;
    private final PrintStream out;
    private final boolean colorEnabled;

    public ReplIo() {
        this(new BufferedReader(new InputStreamReader(System.in)), System.out,
                System.console() != null);
    }

    ReplIo(BufferedReader reader, PrintStream out, boolean colorEnabled) {
        this.reader = reader;
        this.out = out;
        this.colorEnabled = colorEnabled;
    }

    @Override
    public String readLine() throws IOException {
        return reader.readLine();
    }

    @Override
    public void write(String text) {
        out.print(text);
        out.flush();
    }

    @Override
    public void writeLine(String line) {
        out.println(line);
    }

    @Override
    public void writeReasoning(String text) {
        if (colorEnabled) {
            out.print(YELLOW);
            out.print(text);
            out.print(RESET);
        } else {
            out.print(text);
        }
        out.flush();
    }
}
```

- [ ] **Step 5: Update `StubIo` in `src/test/java/com/mrsmith/chat/ChatSessionTest.java`**

Add this method to the `StubIo` class (after `writeLine`):

```java
        @Override
        public void writeReasoning(String text) {
            lines.add(text);
        }
```

This keeps the `IO` interface change compiling; the reasoning path is not exercised until Task 5.

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -q test -Dtest=ReplIoTest`
Expected: PASS — 5 tests, `BUILD SUCCESS`.

- [ ] **Step 7: Run the full suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` — 73 tests green.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/mrsmith/io/IO.java src/main/java/com/mrsmith/io/ReplIo.java src/test/java/com/mrsmith/io/ReplIoTest.java src/test/java/com/mrsmith/chat/ChatSessionTest.java
git commit -m "feat: IO writeReasoning with ANSI-yellow output when TTY"
```

---

### Task 3: ProviderException partialThinking

**Files:**
- Modify: `src/main/java/com/mrsmith/provider/ProviderException.java`
- Modify: `src/test/java/com/mrsmith/provider/ProviderExceptionTest.java`

- [ ] **Step 1: Write the failing test — add this test to `ProviderExceptionTest.java`**

```java
    @Test
    void exposesOptionalPartialThinking() {
        ProviderException e = new ProviderException("Stream interrupted", null, "partial", "think");
        assertTrue(e.hasPartialContent());
        assertEquals("partial", e.partialContent());
        assertEquals("think", e.partialThinking());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ProviderExceptionTest`
Expected: FAIL — compilation error, 4-arg constructor / `partialThinking()` not defined.

- [ ] **Step 3: Modify `ProviderException.java`** — replace the ENTIRE file:

```java
package com.mrsmith.provider;

public class ProviderException extends RuntimeException {

    private final String partialContent;
    private final String partialThinking;

    public ProviderException(String message) {
        this(message, null, null, null);
    }

    public ProviderException(String message, Throwable cause) {
        this(message, cause, null, null);
    }

    public ProviderException(String message, Throwable cause, String partialContent) {
        this(message, cause, partialContent, null);
    }

    public ProviderException(String message, Throwable cause, String partialContent, String partialThinking) {
        super(message, cause);
        this.partialContent = partialContent;
        this.partialThinking = partialThinking;
    }

    public boolean hasPartialContent() {
        return partialContent != null;
    }

    public String partialContent() {
        return partialContent;
    }

    public String partialThinking() {
        return partialThinking;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=ProviderExceptionTest`
Expected: PASS — 2 tests, `BUILD SUCCESS`. The full suite still compiles (existing 1/2/3-arg constructors preserved).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/provider/ProviderException.java src/test/java/com/mrsmith/provider/ProviderExceptionTest.java
git commit -m "feat: carry partial thinking on interrupted-stream exceptions"
```

---

### Task 4: SseResult thinking and SseParser Reasoning Extraction

**Files:**
- Modify: `src/main/java/com/mrsmith/provider/SseResult.java`
- Modify: `src/main/java/com/mrsmith/provider/SseParser.java`
- Modify: `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java` (one line — keep the build green)
- Modify: `src/test/java/com/mrsmith/provider/SseParserTest.java`

- [ ] **Step 1: Write the failing tests — replace `SseParserTest.java` content entirely**

```java
package com.mrsmith.provider;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SseParserTest {

    @Test
    void extractsDeltasInOrder() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"content":"Hello"}}]}

                data: {"choices":[{"delta":{"content":" world"}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add, s -> { });
        assertEquals("Hello world", result.content());
        assertEquals(List.of("Hello", " world"), deltas);
        assertNull(result.usage());
    }

    @Test
    void ignoresChunksWithoutContent() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"role":"assistant"}}]}

                data: {"choices":[{"delta":{"content":"hi"}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add, s -> { });
        assertEquals("hi", result.content());
        assertEquals(List.of("hi"), deltas);
    }

    @Test
    void usesPartialTextWhenStreamEndsWithoutDone() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"content":"partial"}}]}
                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add, s -> { });
        assertEquals("partial", result.content());
    }

    @Test
    void skipsMalformedLinesAndContinues() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: not-json

                data: {"choices":[{"delta":{"content":"ok"}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add, s -> { });
        assertEquals("ok", result.content());
        assertEquals(List.of("ok"), deltas);
    }

    @Test
    void extractsUsageFromChunk() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"content":"hi"}}]}

                data: {"usage":{"prompt_tokens":1200,"completion_tokens":300}}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add, s -> { });
        assertEquals("hi", result.content());
        assertEquals(new Usage(1200, 300), result.usage());
    }

    @Test
    void malformedUsageDoesNotBreakStream() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"usage":"oops","choices":[{"delta":{"content":"hi"}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add, s -> { });
        assertEquals("hi", result.content());
        assertNull(result.usage());
    }

    @Test
    void emptyUsageObjectYieldsNullUsage() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"usage":{}}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add, s -> { });
        assertEquals("", result.content());
        assertNull(result.usage());
    }

    @Test
    void extractsReasoningFromReasoningContent() throws Exception {
        List<String> deltas = new ArrayList<>();
        List<String> reasoning = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"reasoning_content":"think "}}]}

                data: {"choices":[{"delta":{"reasoning_content":"hard"}}]}

                data: {"choices":[{"delta":{"content":"answer"}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add, reasoning::add);
        assertEquals("answer", result.content());
        assertEquals("think hard", result.thinking());
        assertEquals(List.of("think ", "hard"), reasoning);
    }

    @Test
    void fallsBackToReasoningField() throws Exception {
        List<String> reasoning = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"reasoning":"ponder"}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), s -> { }, reasoning::add);
        assertEquals("ponder", result.thinking());
        assertEquals(List.of("ponder"), reasoning);
    }

    @Test
    void thinkingIsNullWhenNoReasoningFields() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"content":"hi"}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), s -> { }, s -> { });
        assertEquals("hi", result.content());
        assertNull(result.thinking());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=SseParserTest`
Expected: FAIL — compilation errors: `consume` signature changed, `SseResult`/`thinking()` mismatch.

- [ ] **Step 3: Modify `SseResult.java`** — replace the ENTIRE file:

```java
package com.mrsmith.provider;

public record SseResult(String content, String thinking, Usage usage) {
}
```

- [ ] **Step 4: Modify `SseParser.java`** — replace the ENTIRE file:

```java
package com.mrsmith.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.function.Consumer;

public final class SseParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SseParser() {
    }

    public static SseResult consume(BufferedReader reader, Consumer<String> contentSink,
                                    Consumer<String> reasoningSink) throws IOException {
        StringBuilder content = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        Usage usage = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring("data:".length()).trim();
            if (payload.equals("[DONE]")) {
                break;
            }
            if (payload.isEmpty()) {
                continue;
            }
            JsonNode node = parse(payload);
            if (node == null) {
                continue;
            }
            Usage chunkUsage = extractUsage(node);
            if (chunkUsage != null) {
                usage = chunkUsage;
            }
            JsonNode delta = node.path("choices").path(0).path("delta");
            if (!delta.isMissingNode()) {
                String reasoning = extractReasoning(delta);
                if (reasoning != null && !reasoning.isEmpty()) {
                    reasoningSink.accept(reasoning);
                    thinking.append(reasoning);
                }
                String contentDelta = delta.path("content").asText(null);
                if (contentDelta != null && !contentDelta.isEmpty()) {
                    contentSink.accept(contentDelta);
                    content.append(contentDelta);
                }
            }
        }
        return new SseResult(content.toString(), thinking.isEmpty() ? null : thinking.toString(), usage);
    }

    private static String extractReasoning(JsonNode delta) {
        String reasoning = delta.path("reasoning_content").asText(null);
        if (reasoning == null) {
            reasoning = delta.path("reasoning").asText(null);
        }
        return reasoning;
    }

    private static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (IOException e) {
            System.err.println("Warning: malformed SSE chunk, skipping: " + payload);
            return null;
        }
    }

    private static Usage extractUsage(JsonNode node) {
        JsonNode usageNode = node.path("usage");
        if (usageNode.isMissingNode() || usageNode.isNull()) {
            return null;
        }
        Integer prompt = usageNode.hasNonNull("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : null;
        Integer completion = usageNode.hasNonNull("completion_tokens") ? usageNode.get("completion_tokens").asInt() : null;
        if (prompt == null && completion == null) {
            return null;
        }
        return new Usage(prompt, completion);
    }
}
```

- [ ] **Step 5: Keep the provider compiling — one line in `OpenAiCompatibleProvider.java`**

In `handleResponse`, change:

```java
            SseResult result = SseParser.consume(reader, sink);
```

to:

```java
            SseResult result = SseParser.consume(reader, sink, s -> { });
```

The reasoning is temporarily discarded at the provider; Task 5 wires it through `send`.

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -q test -Dtest=SseParserTest`
Expected: PASS — 10 tests, `BUILD SUCCESS`.

- [ ] **Step 7: Run the full suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` — all tests green (the provider still compiles via Step 5; behavior unchanged).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/mrsmith/provider/SseResult.java src/main/java/com/mrsmith/provider/SseParser.java src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java src/test/java/com/mrsmith/provider/SseParserTest.java
git commit -m "feat: SseParser extracts and streams reasoning deltas"
```

---

### Task 5: Reasoning Pipeline (Provider, ChatSession, Fakes)

**Files:**
- Modify: `src/main/java/com/mrsmith/provider/Provider.java`
- Modify: `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java`
- Modify: `src/main/java/com/mrsmith/chat/ChatSession.java`
- Modify: `src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java`
- Modify: `src/test/java/com/mrsmith/chat/ChatSessionTest.java`

Follow TDD for the new tests; the existing tests are updated for the new signatures.

- [ ] **Step 1: Change the `Provider` interface** — replace `src/main/java/com/mrsmith/provider/Provider.java`:

```java
package com.mrsmith.provider;

import java.util.List;
import java.util.function.Consumer;

public interface Provider {

    ProviderResponse send(List<ChatMessage> history, Consumer<String> tokenSink,
                          Consumer<String> reasoningSink);
}
```

- [ ] **Step 2: Update `OpenAiCompatibleProvider.java`** — replace the ENTIRE file:

```java
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
    public ProviderResponse send(List<ChatMessage> history, Consumer<String> tokenSink,
                                 Consumer<String> reasoningSink) {
        try {
            return doSend(history, tokenSink, reasoningSink);
        } catch (ProviderException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Network error while contacting "
                    + config.baseUrl() + ": " + e.getMessage(), e);
        }
    }

    private ProviderResponse doSend(List<ChatMessage> history, Consumer<String> tokenSink,
                                    Consumer<String> reasoningSink)
            throws IOException, InterruptedException {
        HttpRequest request = buildRequest(buildRequestBody(history));
        HttpResponse<InputStream> response = sendWithRetry(request);
        return handleResponse(response, history, tokenSink, reasoningSink);
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

    private ProviderResponse handleResponse(HttpResponse<InputStream> response, List<ChatMessage> history,
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
            ChatMessage message = new ChatMessage(Role.ASSISTANT, result.content(), result.thinking());
            Usage usage = result.usage();
            boolean estimated = false;
            if (usage == null) {
                usage = estimateUsage(history, result.content(), result.thinking());
                estimated = true;
            }
            return new ProviderResponse(message, usage, estimated);
        } catch (IOException e) {
            String text = partial.isEmpty() ? null : partial.toString();
            String thinking = partialThinking.isEmpty() ? null : partialThinking.toString();
            throw new ProviderException(text == null
                    ? "Network error during request: " + e.getMessage()
                    : "Stream interrupted: " + e.getMessage(), e, text, thinking);
        }
    }

    private Usage estimateUsage(List<ChatMessage> history, String replyContent, String thinking) {
        int prompt = 0;
        if (config.systemPrompt() != null) {
            prompt += TokenEstimator.estimateTokens(config.systemPrompt());
        }
        for (ChatMessage message : history) {
            prompt += TokenEstimator.estimateTokens(message.content());
        }
        int completion = TokenEstimator.estimateTokens(replyContent);
        if (thinking != null) {
            completion += TokenEstimator.estimateTokens(thinking);
        }
        return new Usage(prompt, completion);
    }

    private String buildRequestBody(List<ChatMessage> history) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);
        if (config.includeUsage()) {
            root.putObject("stream_options").put("include_usage", true);
        }
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
```

- [ ] **Step 3: Update `OpenAiCompatibleProviderTest.java`**

Every `provider.send(...)` call in the file needs a third argument. Concretely:
- Calls ending in `, s -> { })` become `, s -> { }, s -> { })`.
- Calls ending in `, deltas::add)` become `, deltas::add, s -> { })`.

There are 11 call sites. Then add these two new tests at the end of the class (before the closing brace):

```java
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
        ProviderResponse response = provider.send(List.of(new ChatMessage(Role.USER, "hi")), content::add, reasoning::add);
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
        ProviderResponse first = provider.send(history, s -> { }, s -> { });
        assertEquals("secret thinking", first.message().thinking());
        RecordedRequest firstRequest = server.takeRequest();
        String firstBody = firstRequest.getBody().readUtf8();
        assertTrue(firstBody.contains("\"content\":\"first\""));
        assertFalse(firstBody.contains("secret thinking"));
        history.add(first.message());
        history.add(new ChatMessage(Role.USER, "second"));
        provider.send(history, s -> { }, s -> { });
        RecordedRequest secondRequest = server.takeRequest();
        String secondBody = secondRequest.getBody().readUtf8();
        assertTrue(secondBody.contains("\"content\":\"hello\""));
        assertFalse(secondBody.contains("secret thinking"));
    }
```

- [ ] **Step 4: Update `ChatSession.java`** — replace the ENTIRE file:

```java
package com.mrsmith.chat;

import com.mrsmith.config.AppConfig;
import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderException;
import com.mrsmith.provider.ProviderResponse;
import com.mrsmith.provider.Role;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ChatSession {

    private static final int WARN_THRESHOLD_PERCENT = 85;
    private static final int LIMIT_PERCENT = 100;

    private final Provider provider;
    private final IO io;
    private final AppConfig config;
    private final List<ChatMessage> history = new ArrayList<>();
    private final UsageTracker tracker = new UsageTracker();
    private boolean warned85;
    private boolean warned100;

    public ChatSession(Provider provider, IO io, AppConfig config) {
        this.provider = provider;
        this.io = io;
        this.config = config;
    }

    public void run() throws IOException {
        io.writeLine("Mr Smith. Type /help for commands, /exit to quit.");
        String line;
        while ((line = io.readLine()) != null) {
            if (line.equals("/exit")) {
                break;
            }
            if (handleCommand(line)) {
                continue;
            }
            history.add(new ChatMessage(Role.USER, line));
            try {
                ProviderResponse response = provider.send(history, io::write, io::writeReasoning);
                history.add(response.message());
                io.writeLine("");
                tracker.recordTurn(response.usage(), response.usageEstimated());
                String usageLine = tracker.lastTurnLine();
                if (!usageLine.isEmpty()) {
                    io.writeLine(usageLine);
                }
                warnIfNearLimit();
            } catch (ProviderException e) {
                if (e.hasPartialContent() || e.partialThinking() != null) {
                    history.add(new ChatMessage(Role.ASSISTANT, e.partialContent(), e.partialThinking()));
                }
                io.writeLine("");
                io.writeLine("Error: " + e.getMessage());
            } catch (RuntimeException e) {
                io.writeLine("");
                io.writeLine("Error: " + e.getMessage());
            }
        }
    }

    private void warnIfNearLimit() {
        if (!contextLimitConfigured()) {
            return;
        }
        int pct = pctOfMax();
        if (pct >= LIMIT_PERCENT) {
            if (!warned100) {
                warned100 = true;
                io.writeLine(String.format(Locale.US,
                        "Warning: session reached 100%% of your configured %,d-token context limit — consider /reset",
                        config.maxContextTokens()));
            }
        } else if (pct >= WARN_THRESHOLD_PERCENT) {
            if (!warned85) {
                warned85 = true;
                io.writeLine(String.format(Locale.US,
                        "Warning: session at %d%% of your configured %,d-token context limit — consider /reset",
                        pct, config.maxContextTokens()));
            }
        }
    }

    private boolean contextLimitConfigured() {
        Integer maxContext = config.maxContextTokens();
        return maxContext != null && maxContext > 0;
    }

    private int pctOfMax() {
        return (int) Math.round(tracker.totalTokens() * 100.0 / config.maxContextTokens());
    }

    private boolean handleCommand(String line) {
        if (!line.startsWith("/")) {
            return false;
        }
        switch (line) {
            case "/reset" -> {
                history.clear();
                tracker.reset();
                warned85 = false;
                warned100 = false;
                io.writeLine("History cleared.");
            }
            case "/usage" -> io.writeLine(usageReport());
            case "/help" -> io.writeLine("Commands: /exit, /reset, /help, /usage. Anything else is sent to the LLM.");
            default -> io.writeLine("Unknown command: " + line + " (type /help)");
        }
        return true;
    }

    private String usageReport() {
        StringBuilder report = new StringBuilder(tracker.usageReport());
        if (contextLimitConfigured()) {
            report.append(String.format(Locale.US, "%n  context limit: %,d configured (%d%% used)",
                    config.maxContextTokens(), pctOfMax()));
        }
        report.append(String.format(Locale.US, "%n  history: %d messages", history.size()));
        return report.toString();
    }
}
```

- [ ] **Step 5: Update `ChatSessionTest.java`**

1. The three throwing lambda providers change to the 3-arg signature. For example, the `failing` in `providerErrorIsShownAndLoopContinues`:

```java
        Provider failing = (history, sink, reasoningSink) -> {
            throw new ProviderException("HTTP 401: bad key");
        };
```

Do the same for the lambda in `genericProviderFailureIsShownAndLoopContinues` and the `interrupted` lambda in `partialContentFromInterruptedStreamIsKeptInHistory` (which becomes `(history, sink, reasoningSink) -> { sink.accept("partial"); throw new ProviderException("Stream interrupted", null, "partial"); }`).

2. `FakeProvider` becomes:

```java
    static class FakeProvider implements Provider {
        final Usage turnUsage;
        final boolean estimated;
        final String thinking;
        final List<List<ChatMessage>> receivedHistories = new ArrayList<>();
        int calls = 0;

        FakeProvider() {
            this(new Usage(0, 0), false, null);
        }

        FakeProvider(Usage turnUsage, boolean estimated) {
            this(turnUsage, estimated, null);
        }

        FakeProvider(Usage turnUsage, boolean estimated, String thinking) {
            this.turnUsage = turnUsage;
            this.estimated = estimated;
            this.thinking = thinking;
        }

        @Override
        public ProviderResponse send(List<ChatMessage> history, Consumer<String> tokenSink,
                                     Consumer<String> reasoningSink) {
            receivedHistories.add(new ArrayList<>(history));
            calls++;
            ChatMessage last = history.get(history.size() - 1);
            String reply = last.content() + " response";
            tokenSink.accept(reply);
            if (thinking != null) {
                reasoningSink.accept(thinking);
            }
            return new ProviderResponse(new ChatMessage(Role.ASSISTANT, reply, thinking), turnUsage, estimated);
        }
    }
```

3. `FirstThenProvider.send` becomes:

```java
        @Override
        public ProviderResponse send(List<ChatMessage> history, Consumer<String> tokenSink,
                                     Consumer<String> reasoningSink) {
            if (calls++ == 0) {
                return first.send(history, tokenSink, reasoningSink);
            }
            return then.send(history, tokenSink, reasoningSink);
        }
```

4. Add these three new tests (before the `config()` helpers):

```java
    @Test
    void streamsReasoningThroughIo() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(100, 50), true, "ponder");
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(provider, io, config());
        session.run();
        assertTrue(io.lines.contains("ponder"));
    }

    @Test
    void storesThinkingInHistory() throws Exception {
        FakeProvider ok = new FakeProvider(new Usage(0, 0), false, "ponder");
        StubIo io = new StubIo(List.of("first", "second", "/exit"));
        ChatSession session = new ChatSession(ok, io, config());
        session.run();
        List<ChatMessage> secondTurn = ok.receivedHistories.get(1);
        assertEquals(3, secondTurn.size());
        assertEquals("ponder", secondTurn.get(1).thinking());
    }

    @Test
    void interruptedReasoningPreservesPartialThinking() throws Exception {
        Provider interrupted = (history, sink, reasoningSink) -> {
            reasoningSink.accept("half");
            throw new ProviderException("Stream interrupted", null, null, "half");
        };
        FakeProvider ok = new FakeProvider(new Usage(0, 0), false);
        StubIo io = new StubIo(List.of("hello", "again", "/exit"));
        ChatSession session = new ChatSession(new FirstThenProvider(interrupted, ok), io, config());
        session.run();
        List<ChatMessage> secondTurn = ok.receivedHistories.get(0);
        assertEquals(3, secondTurn.size());
        assertEquals(Role.ASSISTANT, secondTurn.get(1).role());
        assertEquals("half", secondTurn.get(1).thinking());
    }
```

Note: in `storesThinkingInHistory`, after two turns the second-turn history passed to the provider is `[user first, assistant(first, "ponder"), user second]` → size 3.

- [ ] **Step 6: Run the affected tests to verify they pass**

Run: `mvn -q test -Dtest=OpenAiCompatibleProviderTest,ChatSessionTest`
Expected: PASS — 13 provider tests + 17 session tests, `BUILD SUCCESS`.

- [ ] **Step 7: Run the full suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` — all tests green (73 + 5 new = 78; verify the count is green).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/mrsmith/provider/Provider.java src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java src/main/java/com/mrsmith/chat/ChatSession.java src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java src/test/java/com/mrsmith/chat/ChatSessionTest.java
git commit -m "feat: stream and store reasoning through the provider and session"
```

---

### Task 6: Final Verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` — all tests green.

- [ ] **Step 2: Package the runnable jar**

Run: `mvn -q package`
Expected: `BUILD SUCCESS`; `target/mr-smith.jar` exists.

- [ ] **Step 3: Smoke-test `--help`**

Run: `java -jar target/mr-smith.jar --help`
Expected: usage unchanged (no new flags for this feature); exit code 0.

- [ ] **Step 4: Manual smoke test (user)**

With your real config at `~/.config/mrsmith/config.json`:
1. Start a chat with a reasoning-capable model (e.g. a DeepSeek-style model on opencode-go).
2. Send a message and confirm the thinking appears in **yellow** (if your terminal shows color) BEFORE the visible answer.
3. Confirm the visible answer streams in the normal color afterwards.
4. Confirm the per-turn usage line and `/usage` still work.
5. Pipe the output to a file (`java -jar target/mr-smith.jar | tee out.txt`) and confirm the thinking is present but contains no ANSI escape codes.
6. Ask a follow-up question and confirm the model does not receive the previous thinking (behavior is correct if the answer doesn't re-trace the reasoning).
