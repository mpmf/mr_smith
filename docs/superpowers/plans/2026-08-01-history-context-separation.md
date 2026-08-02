# Mr Smith — History vs Context Separation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Separate the concept of **context** (the message list sent to the provider) from **history** (the full conversation record stored and transcribed), via a **stateful, incremental** `ContextBuilder` port that future strategies (windowing, compaction) can implement differently.

**Architecture:** `ContextBuilder` is a stateful port: `start(systemPrompt)` resets the window, `appendUser`/`appendAssistant` feed interactions one at a time, and `messages()` returns the current context. `FullContextBuilder` is the single strategy for now (full accumulation, identical behavior to today). `ChatSession` calls `start` at session start and `/reset`, feeds each interaction, and sends `messages()` to the provider. The provider serializes exactly what it is given and estimates usage over it — it no longer injects the system prompt. History (including thinking) and the transcript are unchanged.

**Tech Stack:** Java 21 · Maven · JUnit 5. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-01-history-context-separation-design.md` (revised: incremental/stateful builder)

**Design notes:**
- The provider change (drop system-prompt injection) and the session change (feed the incremental builder) are **atomic**: they land in one task to avoid a window where the CLI drops or doubles the system prompt.
- `ChatSession` constructor has 5 args `(Provider, IO, AppConfig, TranscriptWriter, ContextBuilder)`.
- `AppConfig.systemPrompt()` stays (used by `ChatSession` → `ContextBuilder.start`); the provider no longer reads it.

## File Structure

All paths relative to repo root `/Users/marcoferreira/Projects/mr_smith`.

**New sources** (`src/main/java/com/mrsmith/chat/`):

| File | Responsibility |
|---|---|
| `ContextBuilder.java` | Stateful port: `start(String)`, `appendUser(String)`, `appendAssistant(String)`, `List<ChatMessage> messages()` |
| `FullContextBuilder.java` | Full-accumulation strategy; `start` seeds system; `messages()` immutable snapshot |

**Modified:**

| File | Change |
|---|---|
| `chat/ChatSession.java` | 5th ctor arg `ContextBuilder`; `start` per session; feed appends; send `messages()` |
| `provider/OpenAiCompatibleProvider.java` | Serialize given messages verbatim; estimate over them; drop system-prompt handling |
| `cli/ChatCommand.java` | Inject `new FullContextBuilder()` |

**Tests** (new): `chat/FullContextBuilderTest`.
**Tests** (modified): `chat/ChatSessionTest`, `provider/OpenAiCompatibleProviderTest`.

## Build & Test Commands

- Compile: `mvn -q compile`
- Test: `mvn -q test`
- Single test class: `mvn -q test -Dtest=ClassName`
- Package: `mvn -q package` → `target/mr-smith.jar`

---

### Task 1: ContextBuilder Port and FullContextBuilder (incremental)

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullContextBuilderTest {

    private final FullContextBuilder builder = new FullContextBuilder();

    @Test
    void startSeedsSystemPrompt() {
        builder.start("You are helpful");
        List<ChatMessage> context = builder.messages();
        assertEquals(1, context.size());
        assertEquals(Role.SYSTEM, context.get(0).role());
        assertEquals("You are helpful", context.get(0).content());
    }

    @Test
    void startWithoutSystemPromptSeedsNothing() {
        builder.start(null);
        assertTrue(builder.messages().isEmpty());
    }

    @Test
    void appendsAccumulateInOrder() {
        builder.start(null);
        builder.appendUser("hello");
        builder.appendAssistant("hi");
        builder.appendUser("again");
        List<ChatMessage> context = builder.messages();
        assertEquals(3, context.size());
        assertEquals("hello", context.get(0).content());
        assertEquals("hi", context.get(1).content());
        assertEquals("again", context.get(2).content());
    }

    @Test
    void startResetsTheWindow() {
        builder.start(null);
        builder.appendUser("one");
        builder.start("sys");
        List<ChatMessage> context = builder.messages();
        assertEquals(1, context.size());
        assertEquals("sys", context.get(0).content());
    }

    @Test
    void messagesHasNoThinking() {
        builder.start(null);
        builder.appendAssistant("answer");
        ChatMessage message = builder.messages().get(0);
        assertEquals(Role.ASSISTANT, message.role());
        assertEquals("answer", message.content());
        assertTrue(message.thinking() == null);
    }

    @Test
    void messagesIsImmutable() {
        builder.start(null);
        builder.appendUser("hello");
        List<ChatMessage> context = builder.messages();
        assertThrows(UnsupportedOperationException.class, () -> context.add(new ChatMessage(Role.USER, "x")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=FullContextBuilderTest`
Expected: FAIL — compilation error, `FullContextBuilder` not defined (or the wrong old interface from the previous branch work, if present).

- [ ] **Step 3: Create `ContextBuilder.java`**

```java
package com.mrsmith.chat;

import com.mrsmith.provider.ChatMessage;

import java.util.List;

public interface ContextBuilder {

    void start(String systemPrompt);

    void appendUser(String content);

    void appendAssistant(String content);

    List<ChatMessage> messages();
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

    private final List<ChatMessage> context = new ArrayList<>();

    @Override
    public void start(String systemPrompt) {
        context.clear();
        if (systemPrompt != null) {
            context.add(new ChatMessage(Role.SYSTEM, systemPrompt));
        }
    }

    @Override
    public void appendUser(String content) {
        context.add(new ChatMessage(Role.USER, content));
    }

    @Override
    public void appendAssistant(String content) {
        context.add(new ChatMessage(Role.ASSISTANT, content));
    }

    @Override
    public List<ChatMessage> messages() {
        return List.copyOf(context);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=FullContextBuilderTest`
Expected: PASS — 6 tests, `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ContextBuilder.java src/main/java/com/mrsmith/chat/FullContextBuilder.java src/test/java/com/mrsmith/chat/FullContextBuilderTest.java
git commit -m "feat: incremental ContextBuilder port and FullContextBuilder"
```

---

### Task 2: Atomic Switchover (ChatSession feeds the builder; Provider serializes verbatim)

This task is atomic: it moves system-prompt handling out of the provider and into the session's builder seeding, and wires the incremental builder into `ChatSession`.

**Files:**
- Modify: `src/main/java/com/mrsmith/chat/ChatSession.java`
- Modify: `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java`
- Modify: `src/main/java/com/mrsmith/cli/ChatCommand.java`
- Modify: `src/test/java/com/mrsmith/chat/ChatSessionTest.java`
- Modify: `src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java`

Follow TDD for the new/modified tests; the whole task lands as one coherent commit.

- [ ] **Step 1: Update `ChatSessionTest.java`**

`ContextBuilder` and `FullContextBuilder` are in the SAME package as `ChatSessionTest` (`com.mrsmith.chat`) — NO new imports are needed. (`AppConfig` and `Role` imports already exist.)

1. Every `ChatSession` construction gains a 5th argument: declare `ContextBuilder contextBuilder = new FullContextBuilder();` and construct `new ChatSession(provider, io, config(...), transcripts, contextBuilder)`.

2. The existing `receivedHistories` content assertions still hold: with `systemPrompt` null in the test `config()` helper, the builder's context equals the history contents (no thinking). 

3. **`thinkingIsNotSentToProvider`** (renamed from `storesThinkingInHistory`): with a `FakeProvider` that streams thinking `"ponder"`, the provider receives the builder's context, which has no thinking:

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

5. **Rework `interruptedReasoningPreservesPartialThinking`** — its last assertion on the provider-received context is now on the builder's context (thinking stripped). Keep the role assertion and verify the partial thinking is preserved in the TRANSCRIPT instead:

```java
        assertEquals(Role.ASSISTANT, secondTurn.get(1).role());
        assertNull(secondTurn.get(1).thinking());
        assertEquals(Arrays.asList("half", null), transcripts.assistantThinkings);
```

(Add `import java.util.Arrays;` if needed.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ChatSessionTest`
Expected: FAIL — compilation error, 5-arg `ChatSession` constructor not defined.

- [ ] **Step 3: Modify `src/main/java/com/mrsmith/chat/ChatSession.java`**

Add a field (after `transcripts`):

```java
    private final ContextBuilder contextBuilder;
```

Change the constructor to 5 args:

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

In `run()`, call `contextBuilder.start(config.systemPrompt());` right after the banner (alongside `startNewSession()`), and change the turn flow. Specifically, after the banner line:

```java
        contextBuilder.start(config.systemPrompt());
```

Change the turn body from:

```java
            history.add(new ChatMessage(Role.USER, line));
            appendUser(line);
            try {
                ProviderResponse response = provider.send(context, io::write, io::writeReasoning);
```

(removing the previous `List<ChatMessage> context = contextBuilder.build(...)` line) to:

```java
            history.add(new ChatMessage(Role.USER, line));
            appendUser(line);
            contextBuilder.appendUser(line);
            try {
                List<ChatMessage> context = contextBuilder.messages();
                ProviderResponse response = provider.send(context, io::write, io::writeReasoning);
                history.add(response.message());
                contextBuilder.appendAssistant(response.message().content());
                appendAssistant(response.message().content(), response.message().thinking(),
                        response.usage(), response.usageEstimated());
```

And in the `ProviderException` partial branch, add `contextBuilder.appendAssistant(e.partialContent());` after the `history.add(...)` line.

In `handleCommand`, the `/reset` branch gains `contextBuilder.start(config.systemPrompt());` (after `history.clear()`).

(`history` still stores user messages and replies unchanged; `java.util.List` is already imported.)

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

- [ ] **Step 5: Modify `src/main/java/com/mrsmith/cli/ChatCommand.java`**

Add the imports:

```java
import com.mrsmith.chat.ContextBuilder;
import com.mrsmith.chat.FullContextBuilder;
```

Replace the session construction block (the `TranscriptWriter transcripts = new FileTranscriptWriter(config.sessionsDir());` line stays above it):

```java
        ContextBuilder contextBuilder = new FullContextBuilder();
        ChatSession session = new ChatSession(provider, io, config, transcripts, contextBuilder);
```

- [ ] **Step 6: Update `src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java`**

Replace the `includesSystemPromptWhenConfigured`/`serializesSystemMessageVerbatim` test with:

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
Expected: `BUILD SUCCESS` — all tests green.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ChatSession.java src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java src/main/java/com/mrsmith/cli/ChatCommand.java src/test/java/com/mrsmith/chat/ChatSessionTest.java src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java
git commit -m "refactor: feed incremental ContextBuilder; provider serializes verbatim"
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
4. Ask a follow-up and confirm the model still has context (multi-turn works).
