# Mr Smith — History vs Context Separation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Separate the concept of **context** (the message list sent to the provider) from **history** (the full conversation record stored and transcribed), via a `ContextBuilder` port, so compaction can later change only how the context is derived.

**Architecture:** A `ContextBuilder` port with a `FullContextBuilder` implementation derives the context each turn: system prompt (if any) + all history messages with `thinking` stripped. `ChatSession` builds the context and passes it to `provider.send`. The provider serializes exactly the messages it is given and estimates usage over them — it no longer injects the system prompt. History (including thinking) and the transcript are unchanged.

**Tech Stack:** Java 21 · Maven · JUnit 5. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-01-history-context-separation-design.md`

**Design notes:**
- The provider change (drop system-prompt injection) and the session change (build the context including system) are **atomic**: they land in one task to avoid a window where the CLI drops or doubles the system prompt.
- `ChatSession` constructor grows to 5 args `(Provider, IO, AppConfig, TranscriptWriter, ContextBuilder)`.
- `AppConfig.systemPrompt()` stays (used by `ChatSession` → `FullContextBuilder`); the provider no longer reads it.

## File Structure

All paths relative to repo root `/Users/marcoferreira/Projects/mr_smith`.

**New sources** (`src/main/java/com/mrsmith/chat/`):

| File | Responsibility |
|---|---|
| `ContextBuilder.java` | Port: `List<ChatMessage> build(List<ChatMessage> history, String systemPrompt)` |
| `FullContextBuilder.java` | System message (if any) + history messages rebuilt without thinking |

**Modified:**

| File | Change |
|---|---|
| `chat/ChatSession.java` | 5th ctor arg `ContextBuilder`; build context per turn and pass to provider |
| `provider/OpenAiCompatibleProvider.java` | Serialize given messages verbatim; estimate over them; drop system-prompt handling |
| `cli/ChatCommand.java` | Inject `new FullContextBuilder()` |

**Tests** (new): `chat/FullContextBuilderTest`.
**Tests** (modified): `chat/ChatSessionTest`, `provider/OpenAiCompatibleProviderTest`.

## Build & Test Commands

- Compile: `mvn -q compile`
- Test: `mvn -q test`
- Single test class: `mvn -q test -Dtest=ClassName`
- Package: `mvn -q package` → `target/mr-smith.jar`

Current baseline: 102 tests, all green on `master`.

---

### Task 1: ContextBuilder Port and FullContextBuilder

**Files:**
- Create: `src/main/java/com/mrsmith/chat/ContextBuilder.java`
- Create: `src/main/java/com/mrsmith/chat/FullContextBuilder.java`
- Create: `src/test/java/com/mrsmith/chat/FullContextBuilderTest.java`

- [ ] **Step 1: Write the failing test** — create `src/test/java/com/mrsmith/chat/FullContextBuilderTest.java`:

```java
package com.mrsmith.chat;

import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FullContextBuilderTest {

    private final FullContextBuilder builder = new FullContextBuilder();

    @Test
    void prependsSystemPromptAndStripsThinking() {
        List<ChatMessage> history = List.of(
                new ChatMessage(Role.USER, "hello"),
                new ChatMessage(Role.ASSISTANT, "hi", "ponder"));
        List<ChatMessage> context = builder.build(history, "You are helpful");
        assertEquals(3, context.size());
        assertEquals(Role.SYSTEM, context.get(0).role());
        assertEquals("You are helpful", context.get(0).content());
        assertEquals(Role.USER, context.get(1).role());
        assertEquals("hello", context.get(1).content());
        assertEquals(Role.ASSISTANT, context.get(2).role());
        assertEquals("hi", context.get(2).content());
        assertNull(context.get(2).thinking());
    }

    @Test
    void omitsSystemMessageWhenPromptNull() {
        List<ChatMessage> history = List.of(new ChatMessage(Role.USER, "hello"));
        List<ChatMessage> context = builder.build(history, null);
        assertEquals(1, context.size());
        assertEquals("hello", context.get(0).content());
    }

    @Test
    void preservesNullContent() {
        List<ChatMessage> history = List.of(new ChatMessage(Role.ASSISTANT, null, "think"));
        List<ChatMessage> context = builder.build(history, null);
        assertEquals(1, context.size());
        assertNull(context.get(0).content());
        assertNull(context.get(0).thinking());
    }

    @Test
    void emptyHistoryWithSystemPrompt() {
        List<ChatMessage> context = builder.build(List.of(), "sys");
        assertEquals(1, context.size());
        assertEquals(Role.SYSTEM, context.get(0).role());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=FullContextBuilderTest`
Expected: FAIL — compilation error, `FullContextBuilder` not defined.

- [ ] **Step 3: Create `ContextBuilder.java`**

```java
package com.mrsmith.chat;

import com.mrsmith.provider.ChatMessage;

import java.util.List;

public interface ContextBuilder {

    List<ChatMessage> build(List<ChatMessage> history, String systemPrompt);
}
```

- [ ] **Step 4: Create `FullContextBuilder.java`**

```java
package com.mrsmith.chat;

import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Role;

import java.util.ArrayList;
import java.util.List;

public class FullContextBuilder implements ContextBuilder {

    @Override
    public List<ChatMessage> build(List<ChatMessage> history, String systemPrompt) {
        List<ChatMessage> context = new ArrayList<>();
        if (systemPrompt != null) {
            context.add(new ChatMessage(Role.SYSTEM, systemPrompt));
        }
        for (ChatMessage message : history) {
            context.add(new ChatMessage(message.role(), message.content()));
        }
        return context;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=FullContextBuilderTest`
Expected: PASS — 4 tests, `BUILD SUCCESS`.

- [ ] **Step 6: Run the full suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` — all tests green (106 total).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ContextBuilder.java src/main/java/com/mrsmith/chat/FullContextBuilder.java src/test/java/com/mrsmith/chat/FullContextBuilderTest.java
git commit -m "feat: add ContextBuilder port and FullContextBuilder"
```

---

### Task 2: Atomic Switchover (ChatSession builds context; Provider serializes verbatim)

This task is atomic: it moves system-prompt handling out of the provider and into the session's context building, and wires the new 5-arg `ChatSession`.

**Files:**
- Modify: `src/main/java/com/mrsmith/chat/ChatSession.java`
- Modify: `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java`
- Modify: `src/main/java/com/mrsmith/cli/ChatCommand.java`
- Modify: `src/test/java/com/mrsmith/chat/ChatSessionTest.java`
- Modify: `src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java`

Follow TDD for the new/modified tests; the whole task lands as one coherent commit.

- [ ] **Step 1: Update `ChatSessionTest.java`**

1. `ContextBuilder` and `FullContextBuilder` are in the SAME package as `ChatSessionTest` (`com.mrsmith.chat`) — NO new imports are needed. (`AppConfig` and `Role` imports already exist.)

2. Every `ChatSession` construction gains a 5th argument: declare `ContextBuilder contextBuilder = new FullContextBuilder();` and construct `new ChatSession(provider, io, config(...), transcripts, contextBuilder)`.

3. **Rework `storesThinkingInHistory`** — the provider now receives the context (thinking stripped), so the assertion changes. Replace the test:

```java
    @Test
    void thinkingIsNotSentToProvider() throws Exception {
        FakeProvider ok = new FakeProvider(new Usage(0, 0), false, "ponder");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("first", "second", "/exit"));
        ChatSession session = new ChatSession(ok, io, config(), transcripts, new FullContextBuilder());
        session.run();
        List<ChatMessage> secondTurn = ok.receivedHistories.get(1);
        assertEquals(3, secondTurn.size());
        assertEquals("first response", secondTurn.get(1).content());
        assertNull(secondTurn.get(1).thinking());
    }
```

4. Add this new test:

```java
    @Test
    void includesSystemMessageInContext() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        AppConfig cfg = new AppConfig("sk-test", "https://example.com/v1", "test-model", "You are helpful");
        ChatSession session = new ChatSession(provider, io, cfg, transcripts, new FullContextBuilder());
        session.run();
        List<ChatMessage> context = provider.receivedHistories.get(0);
        assertEquals(2, context.size());
        assertEquals(Role.SYSTEM, context.get(0).role());
        assertEquals("You are helpful", context.get(0).content());
    }
```

Note: with `systemPrompt` null in the existing `config()` helper, the context the provider receives equals history contents (thinking stripped), so all other existing `receivedHistories` assertions still hold.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ChatSessionTest`
Expected: FAIL — compilation error, 5-arg `ChatSession` constructor not defined.

- [ ] **Step 3: Modify `src/main/java/com/mrsmith/chat/ChatSession.java`**

Add the import `import com.mrsmith.provider.ChatMessage;` is present. Add a field and constructor arg, and change the `run()` loop to build the context. Specifically:

Add the field (after `transcripts`):

```java
    private final ContextBuilder contextBuilder;
```

Change the constructor:

```java
    public ChatSession(Provider provider, IO io, AppConfig config, TranscriptWriter transcripts,
                       ContextBuilder contextBuilder) {
        this.provider = provider;
        this.io = io;
        this.config = config;
        this.transcripts = transcripts;
        this.contextBuilder = contextBuilder;
    }
```

Change the `send` line in `run()` from:

```java
                ProviderResponse response = provider.send(history, io::write, io::writeReasoning);
```

to:

```java
                List<ChatMessage> context = contextBuilder.build(history, config.systemPrompt());
                ProviderResponse response = provider.send(context, io::write, io::writeReasoning);
```

(The `history` list still stores user messages and replies unchanged.)

- [ ] **Step 4: Modify `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java`** — replace the ENTIRE file:

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
    public ProviderResponse send(List<ChatMessage> context, Consumer<String> tokenSink,
                                 Consumer<String> reasoningSink) {
        try {
            return doSend(context, tokenSink, reasoningSink);
        } catch (ProviderException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Network error while contacting "
                    + config.baseUrl() + ": " + e.getMessage(), e);
        }
    }

    private ProviderResponse doSend(List<ChatMessage> context, Consumer<String> tokenSink,
                                    Consumer<String> reasoningSink)
            throws IOException, InterruptedException {
        HttpRequest request = buildRequest(buildRequestBody(context));
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
            ChatMessage message = new ChatMessage(Role.ASSISTANT, result.content(), result.thinking());
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
            prompt += TokenEstimator.estimateTokens(message.content());
        }
        int completion = TokenEstimator.estimateTokens(replyContent);
        if (thinking != null) {
            completion += TokenEstimator.estimateTokens(thinking);
        }
        return new Usage(prompt, completion);
    }

    private String buildRequestBody(List<ChatMessage> context) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);
        if (config.includeUsage()) {
            root.putObject("stream_options").put("include_usage", true);
        }
        ArrayNode messages = root.putArray("messages");
        for (ChatMessage message : context) {
            String content = message.content() == null ? "" : message.content();
            messages.addObject()
                    .put("role", message.roleName())
                    .put("content", content);
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

Note: the system-prompt block was removed from `buildRequestBody`, and `estimateUsage` now iterates the context (the system message, if present, is one of the context messages). Parameter names are `context` throughout.

- [ ] **Step 5: Update `src/main/java/com/mrsmith/cli/ChatCommand.java`**

Add the imports:

```java
import com.mrsmith.chat.ContextBuilder;
import com.mrsmith.chat.FullContextBuilder;
```

Replace the session construction block:

```java
        ContextBuilder contextBuilder = new FullContextBuilder();
        ChatSession session = new ChatSession(provider, io, config, transcripts, contextBuilder);
```

(The `TranscriptWriter transcripts = new FileTranscriptWriter(config.sessionsDir());` line stays above it.)

- [ ] **Step 6: Update `src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java`**

Rework the `includesSystemPromptWhenConfigured` test. The provider no longer injects the system prompt, so the test must pass the system message in the list explicitly. Replace it with:

```java
    @Test
    void serializesSystemMessageVerbatim() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        List<ChatMessage> context = List.of(
                new ChatMessage(Role.SYSTEM, "You are helpful"),
                new ChatMessage(Role.USER, "hello"));
        provider.send(context, s -> { }, s -> { });
        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"role\":\"system\""));
        assertTrue(body.contains("\"content\":\"You are helpful\""));
    }
```

(No other provider tests change: the `setUp` config has no system prompt, so all other tests pass history/context with just user/assistant messages.)

- [ ] **Step 7: Run the affected tests**

Run: `mvn -q test -Dtest=ChatSessionTest,OpenAiCompatibleProviderTest,FullContextBuilderTest`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 8: Run the full suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` — all tests green. (Baseline 106 from Task 1, plus the new `includesSystemMessageInContext` test; the renamed `thinkingIsNotSentToProvider` keeps the same count.)

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ChatSession.java src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java src/main/java/com/mrsmith/cli/ChatCommand.java src/test/java/com/mrsmith/chat/ChatSessionTest.java src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java
git commit -m "refactor: derive context from history via ContextBuilder; provider serializes verbatim"
```

---

### Task 3: Final Verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` — all tests green.

- [ ] **Step 2: Package the runnable jar**

Run: `mvn -q package`
Expected: `BUILD SUCCESS`; `target/mr-smith.jar` exists.

- [ ] **Step 3: Smoke-test `--help`**

Run: `java -jar target/mr-smith.jar --help`
Expected: unchanged usage; exit code 0.

- [ ] **Step 4: Manual smoke test (user)**

With your real config at `~/.config/mrsmith/config.json`:
1. Run the CLI and send a message; confirm the model still follows your system prompt (behavior unchanged).
2. Confirm thinking still streams in yellow and the transcript still records it.
3. Confirm the per-turn usage line and `/usage` still work.
4. Ask a follow-up and confirm the model still has context (multi-turn works) — the context sent is the same as before (system + full history, minus thinking).
