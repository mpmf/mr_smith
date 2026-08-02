# Mr Smith — Session Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give each chat session a UUID and keep a JSONL transcript of all interactions (user requests, thinking, responses) in `<sessionsDir>/<uuid>/transcript.jsonl`, via a `TranscriptWriter` port so a database backend can replace the filesystem later.

**Architecture:** A `TranscriptWriter` port (keyed by session id) with a `FileTranscriptWriter` adapter that appends JSONL under `<sessionsRoot>/<uuid>/`. `ChatSession` owns the session lifecycle: it generates a UUID at startup and on `/reset`, prints `Session: <uuid>` in the banner, and appends a user record per message and an assistant record per reply (including partial-content recovery). `sessionsDir` is configurable (CLI > env > file > default `~/.config/mrsmith/sessions`).

**Tech Stack:** Java 21 · Maven · JUnit 5 · Jackson (already a dependency). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-01-session-persistence-design.md`

**Design notes:**
- The default `sessionsDir` is resolved in `ConfigLoader` (consistent with how model/baseUrl defaults resolve there), so `AppConfig.sessionsDir()` is non-null in production. `AppConfig`'s 4-arg and 6-arg convenience constructors and `CliConfig`'s 6-arg constructor are preserved so existing call sites/tests keep compiling.
- Transcript logging is best-effort: a failed `start`/`append` warns on stderr and disables logging for the session; the chat continues.

## File Structure

All paths relative to repo root `/Users/marcoferreira/Projects/mr_smith`.

**New production sources** (`src/main/java/com/mrsmith/`):

| File | Responsibility |
|---|---|
| `session/TranscriptWriter.java` | Port: `start(UUID)`, `appendUser(UUID, content)`, `appendAssistant(UUID, content, thinking, usage, estimated)` |
| `session/FileTranscriptWriter.java` | JSONL adapter: creates `<root>/<uuid>/` on start, appends records to `transcript.jsonl` |

**Modified production sources:**

| File | Change |
|---|---|
| `config/AppConfig.java` | Add `Path sessionsDir` (nullable); preserve 4-arg + 6-arg convenience ctors |
| `config/CliConfig.java` | Add `Path sessionsDir`; preserve 6-arg convenience ctor + `empty()` |
| `config/ConfigLoader.java` | Read `sessionsDir` (file/env/CLI), default `~/.config/mrsmith/sessions` |
| `cli/ChatCommand.java` | Add `--sessions-dir`; build `FileTranscriptWriter`, inject into `ChatSession` |
| `chat/ChatSession.java` | Session lifecycle: UUID on start + `/reset`, banner, appends |

**Tests** (new): `session/FileTranscriptWriterTest`.
**Tests** (modified): `config/AppConfigTest`, `config/ConfigLoaderTest`, `chat/ChatSessionTest` (fake `TranscriptWriter` + new tests), `cli/ChatCommandTest` (`--sessions-dir` in help).

## Build & Test Commands

- Compile: `mvn -q compile`
- Test: `mvn -q test`
- Single test class: `mvn -q test -Dtest=ClassName`
- Package: `mvn -q package` → `target/mr-smith.jar`

Current baseline: 85 tests, all green on `master`.

---

### Task 1: AppConfig and CliConfig sessionsDir

**Files:**
- Modify: `src/main/java/com/mrsmith/config/AppConfig.java`
- Modify: `src/main/java/com/mrsmith/config/CliConfig.java`
- Modify: `src/test/java/com/mrsmith/config/AppConfigTest.java`

- [ ] **Step 1: Write the failing tests** — add these two tests to `AppConfigTest.java`:

```java
    @Test
    void fourArgConstructorDefaultsSessionsDirToNull() {
        AppConfig config = new AppConfig("sk", "https://example.com/v1", "gpt", null);
        assertNull(config.sessionsDir());
    }

    @Test
    void sevenArgConstructorPreservesSessionsDir() {
        Path dir = Path.of("/tmp/sessions");
        AppConfig config = new AppConfig("sk", "https://example.com/v1", "gpt", "sys", 8192, false, dir);
        assertEquals(dir, config.sessionsDir());
    }
```

Add the import `import java.nio.file.Path;` to the test file (it already imports `assertEquals`, `assertNull`, `assertTrue`).

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=AppConfigTest`
Expected: FAIL — compilation error, `sessionsDir()` / 7-arg constructor not defined.

- [ ] **Step 3: Modify `AppConfig.java`** — replace the ENTIRE file:

```java
package com.mrsmith.config;

import java.nio.file.Path;
import java.util.Objects;

public record AppConfig(String apiKey, String baseUrl, String model, String systemPrompt,
                        Integer maxContextTokens, boolean includeUsage, Path sessionsDir) {

    public AppConfig {
        Objects.requireNonNull(apiKey, "apiKey is required");
        Objects.requireNonNull(baseUrl, "baseUrl is required");
        Objects.requireNonNull(model, "model is required");
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

    public AppConfig(String apiKey, String baseUrl, String model, String systemPrompt) {
        this(apiKey, baseUrl, model, systemPrompt, null, true, null);
    }

    public AppConfig(String apiKey, String baseUrl, String model, String systemPrompt,
                     Integer maxContextTokens, boolean includeUsage) {
        this(apiKey, baseUrl, model, systemPrompt, maxContextTokens, includeUsage, null);
    }
}
```

- [ ] **Step 4: Modify `CliConfig.java`** — replace the ENTIRE file:

```java
package com.mrsmith.config;

import java.nio.file.Path;

public record CliConfig(String apiKey, String baseUrl, String model, String systemPrompt,
                        Integer maxContextTokens, Boolean includeUsage, Path sessionsDir) {

    public CliConfig(String apiKey, String baseUrl, String model, String systemPrompt,
                     Integer maxContextTokens, Boolean includeUsage) {
        this(apiKey, baseUrl, model, systemPrompt, maxContextTokens, includeUsage, null);
    }

    public static CliConfig empty() {
        return new CliConfig(null, null, null, null, null, null, null);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=AppConfigTest`
Expected: PASS — 4 tests, `BUILD SUCCESS`. The full suite still compiles (4-arg and 6-arg `AppConfig`/`CliConfig` constructors preserved).

- [ ] **Step 6: Run the full suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` — all tests green.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mrsmith/config/AppConfig.java src/main/java/com/mrsmith/config/CliConfig.java src/test/java/com/mrsmith/config/AppConfigTest.java
git commit -m "feat: add sessionsDir to AppConfig and CliConfig"
```

---

### Task 2: ConfigLoader Reads sessionsDir

**Files:**
- Modify: `src/main/java/com/mrsmith/config/ConfigLoader.java`
- Modify: `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`

- [ ] **Step 1: Write the failing tests** — add these four tests to `ConfigLoaderTest.java`:

```java
    @Test
    void sessionsDirDefaultsToConfigHome() throws IOException {
        AppConfig config = ConfigLoader.load(noFile(), CliConfig.empty(), Map.of("OPENAI_API_KEY", "sk-x"));
        assertEquals(Path.of(System.getProperty("user.home"), ".config", "mrsmith", "sessions"),
                config.sessionsDir());
    }

    @Test
    void sessionsDirReadFromFile() throws IOException {
        Path file = writeConfig("{ \"sessionsDir\": \"/tmp/my-sessions\" }");
        AppConfig config = ConfigLoader.load(file, CliConfig.empty(), Map.of("OPENAI_API_KEY", "sk-x"));
        assertEquals(Path.of("/tmp/my-sessions"), config.sessionsDir());
    }

    @Test
    void sessionsDirFromEnv() throws IOException {
        AppConfig config = ConfigLoader.load(noFile(), CliConfig.empty(),
                Map.of("OPENAI_API_KEY", "sk-x", "MRSMITH_SESSIONS_DIR", "/tmp/env-sessions"));
        assertEquals(Path.of("/tmp/env-sessions"), config.sessionsDir());
    }

    @Test
    void cliOverridesSessionsDir() throws IOException {
        Path file = writeConfig("{ \"sessionsDir\": \"/tmp/file-sessions\" }");
        CliConfig cli = new CliConfig(null, null, null, null, null, null, Path.of("/tmp/cli-sessions"));
        AppConfig config = ConfigLoader.load(file, cli, Map.of("OPENAI_API_KEY", "sk-x"));
        assertEquals(Path.of("/tmp/cli-sessions"), config.sessionsDir());
    }
```

Add the import `import java.nio.file.Path;` to the test file.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ConfigLoaderTest`
Expected: FAIL — `sessionsDirDefaultsToConfigHome` fails (`config.sessionsDir()` is null).

- [ ] **Step 3: Modify `ConfigLoader.java`**

Add a `String fileSessionsDir = null;` variable with the other file-* variables, and inside the `if (Files.exists(configFile))` block, after the `includeUsage` read, add:

```java
                if (root.hasNonNull("sessionsDir")) {
                    fileSessionsDir = root.get("sessionsDir").asText();
                }
```

Change the resolution block to add sessionsDir (after the `includeUsage` line):

```java
        Path sessionsDir = Path.of(firstNonNull(cli.sessionsDir() == null ? null : cli.sessionsDir().toString(),
                env.get("MRSMITH_SESSIONS_DIR"), fileSessionsDir,
                Path.of(System.getProperty("user.home"), ".config", "mrsmith", "sessions").toString()));
```

And change the final `return` to pass the sessionsDir:

```java
        return new AppConfig(apiKey, baseUrl, model, systemPrompt,
                maxContext, includeUsage == null || includeUsage, sessionsDir);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=ConfigLoaderTest`
Expected: PASS — 18 tests, `BUILD SUCCESS`.

- [ ] **Step 5: Run the full suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` — all tests green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/config/ConfigLoader.java src/test/java/com/mrsmith/config/ConfigLoaderTest.java
git commit -m "feat: read sessionsDir config with default"
```

---

### Task 3: TranscriptWriter Port and FileTranscriptWriter

**Files:**
- Create: `src/main/java/com/mrsmith/session/TranscriptWriter.java`
- Create: `src/main/java/com/mrsmith/session/FileTranscriptWriter.java`
- Create: `src/test/java/com/mrsmith/session/FileTranscriptWriterTest.java`

- [ ] **Step 1: Write the failing test** — create `src/test/java/com/mrsmith/session/FileTranscriptWriterTest.java`:

```java
package com.mrsmith.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrsmith.provider.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTranscriptWriterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void startCreatesSessionFolder() throws IOException {
        FileTranscriptWriter writer = new FileTranscriptWriter(tempDir);
        UUID id = UUID.randomUUID();
        writer.start(id);
        assertTrue(Files.isDirectory(tempDir.resolve(id.toString())));
    }

    @Test
    void appendsUserAndAssistantRecords() throws IOException {
        FileTranscriptWriter writer = new FileTranscriptWriter(tempDir);
        UUID id = UUID.randomUUID();
        writer.start(id);
        writer.appendUser(id, "hello");
        writer.appendAssistant(id, "hi there", "ponder", new Usage(1200, 300), false);

        Path file = tempDir.resolve(id.toString()).resolve("transcript.jsonl");
        assertTrue(Files.exists(file));
        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());

        JsonNode user = JSON.readTree(lines.get(0));
        assertEquals("user", user.get("type").asText());
        assertEquals("hello", user.get("content").asText());
        assertTrue(user.hasNonNull("timestamp"));

        JsonNode assistant = JSON.readTree(lines.get(1));
        assertEquals("assistant", assistant.get("type").asText());
        assertEquals("hi there", assistant.get("content").asText());
        assertEquals("ponder", assistant.get("thinking").asText());
        assertEquals(1200, assistant.get("promptTokens").asInt());
        assertEquals(300, assistant.get("completionTokens").asInt());
        assertFalse(assistant.get("estimated").asBoolean());
        assertTrue(assistant.hasNonNull("timestamp"));
    }

    @Test
    void omitsThinkingAndUsageWhenNull() throws IOException {
        FileTranscriptWriter writer = new FileTranscriptWriter(tempDir);
        UUID id = UUID.randomUUID();
        writer.start(id);
        writer.appendAssistant(id, "answer", null, null, false);
        JsonNode record = JSON.readTree(
                Files.readString(tempDir.resolve(id.toString()).resolve("transcript.jsonl")));
        assertTrue(record.get("thinking") == null);
        assertTrue(record.get("promptTokens") == null);
    }

    @Test
    void appendsAccumulateAsLines() throws IOException {
        FileTranscriptWriter writer = new FileTranscriptWriter(tempDir);
        UUID id = UUID.randomUUID();
        writer.start(id);
        writer.appendUser(id, "one");
        writer.appendUser(id, "two");
        Path file = tempDir.resolve(id.toString()).resolve("transcript.jsonl");
        assertEquals(2, Files.readAllLines(file).size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=FileTranscriptWriterTest`
Expected: FAIL — compilation error, `FileTranscriptWriter` not defined.

- [ ] **Step 3: Create `TranscriptWriter.java`**

```java
package com.mrsmith.session;

import com.mrsmith.provider.Usage;

import java.io.IOException;
import java.util.UUID;

public interface TranscriptWriter {

    void start(UUID sessionId) throws IOException;

    void appendUser(UUID sessionId, String content) throws IOException;

    void appendAssistant(UUID sessionId, String content, String thinking,
                         Usage usage, boolean estimated) throws IOException;
}
```

- [ ] **Step 4: Create `FileTranscriptWriter.java`**

```java
package com.mrsmith.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.provider.Usage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

public class FileTranscriptWriter implements TranscriptWriter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path sessionsRoot;

    public FileTranscriptWriter(Path sessionsRoot) {
        this.sessionsRoot = sessionsRoot;
    }

    @Override
    public void start(UUID sessionId) throws IOException {
        Files.createDirectories(folder(sessionId));
    }

    @Override
    public void appendUser(UUID sessionId, String content) throws IOException {
        ObjectNode record = JSON.createObjectNode();
        record.put("type", "user");
        record.put("content", content);
        record.put("timestamp", Instant.now().toString());
        append(sessionId, record);
    }

    @Override
    public void appendAssistant(UUID sessionId, String content, String thinking,
                                Usage usage, boolean estimated) throws IOException {
        ObjectNode record = JSON.createObjectNode();
        record.put("type", "assistant");
        record.put("content", content);
        if (thinking != null) {
            record.put("thinking", thinking);
        }
        if (usage != null) {
            if (usage.promptTokens() != null) {
                record.put("promptTokens", usage.promptTokens());
            }
            if (usage.completionTokens() != null) {
                record.put("completionTokens", usage.completionTokens());
            }
        }
        record.put("estimated", estimated);
        record.put("timestamp", Instant.now().toString());
        append(sessionId, record);
    }

    private void append(UUID sessionId, ObjectNode record) throws IOException {
        String line = JSON.writeValueAsString(record);
        Files.writeString(transcriptFile(sessionId), line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private Path folder(UUID sessionId) {
        return sessionsRoot.resolve(sessionId.toString());
    }

    private Path transcriptFile(UUID sessionId) {
        return folder(sessionId).resolve("transcript.jsonl");
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=FileTranscriptWriterTest`
Expected: PASS — 4 tests, `BUILD SUCCESS`.

- [ ] **Step 6: Run the full suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` — all tests green (89 total).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mrsmith/session/ src/test/java/com/mrsmith/session/FileTranscriptWriterTest.java
git commit -m "feat: add TranscriptWriter port and JSONL file adapter"
```

---

### Task 4: ChatSession Session Lifecycle

**Files:**
- Modify: `src/main/java/com/mrsmith/chat/ChatSession.java`
- Modify: `src/test/java/com/mrsmith/chat/ChatSessionTest.java`

- [ ] **Step 1: Write the failing tests** — in `ChatSessionTest.java`, add the fake writer and new tests.

Add the `TranscriptWriter` import: `import com.mrsmith.session.TranscriptWriter;` (the file already imports `ProviderResponse`, `Usage`, etc.).

Add the `FakeTranscriptWriter` class (next to the other fakes):

```java
    static class FakeTranscriptWriter implements TranscriptWriter {
        final List<UUID> starts = new ArrayList<>();
        final List<String> userContents = new ArrayList<>();
        final List<String> assistantContents = new ArrayList<>();
        final List<String> assistantThinkings = new ArrayList<>();
        final List<Usage> assistantUsages = new ArrayList<>();
        final List<Boolean> assistantEstimated = new ArrayList<>();
        boolean failStart;
        boolean failAppend;

        @Override
        public void start(UUID sessionId) throws IOException {
            if (failStart) {
                throw new IOException("boom");
            }
            starts.add(sessionId);
        }

        @Override
        public void appendUser(UUID sessionId, String content) throws IOException {
            if (failAppend) {
                throw new IOException("boom");
            }
            userContents.add(content);
        }

        @Override
        public void appendAssistant(UUID sessionId, String content, String thinking,
                                    Usage usage, boolean estimated) throws IOException {
            if (failAppend) {
                throw new IOException("boom");
            }
            assistantContents.add(content);
            assistantThinkings.add(thinking);
            assistantUsages.add(usage);
            assistantEstimated.add(estimated);
        }
    }
```

Add the import `import java.util.UUID;` to the test file.

Update every existing `new ChatSession(provider, io, config(...))` call to pass a `FakeTranscriptWriter` — e.g. `new ChatSession(provider, io, config(), transcripts)` where `FakeTranscriptWriter transcripts = new FakeTranscriptWriter();` is declared in each test (or use a helper). Concretely, each existing test gains a `FakeTranscriptWriter transcripts = new FakeTranscriptWriter();` line and its `ChatSession` construction becomes `new ChatSession(provider, io, config(), transcripts)` (or `config(1000)` where applicable).

Add these new tests:

```java
    @Test
    void startsSessionAndPrintsUuid() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/exit"));
        ChatSession session = new ChatSession(provider, io, config(), transcripts);
        session.run();
        assertEquals(1, transcripts.starts.size());
        assertTrue(io.lines.stream().anyMatch(l -> l.startsWith("Session: ")));
    }

    @Test
    void recordsUserAndAssistantTurns() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(1200, 300), false);
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(provider, io, config(), transcripts);
        session.run();
        assertEquals(List.of("hello"), transcripts.userContents);
        assertEquals(List.of("hello response"), transcripts.assistantContents);
        assertEquals(new Usage(1200, 300), transcripts.assistantUsages.get(0));
        assertEquals(false, transcripts.assistantEstimated.get(0));
    }

    @Test
    void recordsThinkingOnAssistantTurn() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(0, 0), false, "ponder");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(provider, io, config(), transcripts);
        session.run();
        assertEquals(List.of("ponder"), transcripts.assistantThinkings);
    }

    @Test
    void resetStartsNewSession() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/reset", "/exit"));
        ChatSession session = new ChatSession(provider, io, config(), transcripts);
        session.run();
        assertEquals(2, transcripts.starts.size());
    }

    @Test
    void recordsPartialContentOnInterruption() throws Exception {
        Provider interrupted = (history, sink, reasoningSink) -> {
            sink.accept("partial");
            throw new ProviderException("Stream interrupted", null, "partial");
        };
        FakeProvider ok = new FakeProvider(new Usage(0, 0), false);
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(new FirstThenProvider(interrupted, ok), io, config(), transcripts);
        session.run();
        assertEquals(List.of("partial"), transcripts.assistantContents);
        assertTrue(transcripts.assistantUsages.get(0) == null);
    }

    @Test
    void continuesWhenTranscriptStartFails() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        transcripts.failStart = true;
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(provider, io, config(), transcripts);
        session.run();
        assertEquals(1, provider.calls);
        assertTrue(transcripts.userContents.isEmpty());
    }

    @Test
    void continuesWhenTranscriptAppendFails() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        transcripts.failAppend = true;
        StubIo io = new StubIo(List.of("hello", "again", "/exit"));
        ChatSession session = new ChatSession(provider, io, config(), transcripts);
        session.run();
        assertEquals(2, provider.calls);
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("again response")));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ChatSessionTest`
Expected: FAIL — compilation error, 4-arg `ChatSession` constructor not defined.

- [ ] **Step 3: Modify `ChatSession.java`** — replace the ENTIRE file:

```java
package com.mrsmith.chat;

import com.mrsmith.config.AppConfig;
import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderException;
import com.mrsmith.provider.ProviderResponse;
import com.mrsmith.provider.Role;
import com.mrsmith.provider.Usage;
import com.mrsmith.session.TranscriptWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class ChatSession {

    private static final int WARN_THRESHOLD_PERCENT = 85;
    private static final int LIMIT_PERCENT = 100;

    private final Provider provider;
    private final IO io;
    private final AppConfig config;
    private final TranscriptWriter transcripts;
    private final List<ChatMessage> history = new ArrayList<>();
    private final UsageTracker tracker = new UsageTracker();
    private boolean warned85;
    private boolean warned100;
    private UUID currentSessionId;

    public ChatSession(Provider provider, IO io, AppConfig config, TranscriptWriter transcripts) {
        this.provider = provider;
        this.io = io;
        this.config = config;
        this.transcripts = transcripts;
    }

    public void run() throws IOException {
        io.writeLine("Mr Smith. Type /help for commands, /exit to quit.");
        startNewSession();
        String line;
        while ((line = io.readLine()) != null) {
            if (line.equals("/exit")) {
                break;
            }
            if (handleCommand(line)) {
                continue;
            }
            history.add(new ChatMessage(Role.USER, line));
            appendUser(line);
            try {
                ProviderResponse response = provider.send(history, io::write, io::writeReasoning);
                history.add(response.message());
                appendAssistant(response.message().content(), response.message().thinking(),
                        response.usage(), response.usageEstimated());
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
                    appendAssistant(e.partialContent(), e.partialThinking(), null, false);
                }
                io.writeLine("");
                io.writeLine("Error: " + e.getMessage());
            } catch (RuntimeException e) {
                io.writeLine("");
                io.writeLine("Error: " + e.getMessage());
            }
        }
    }

    private void startNewSession() {
        UUID id = UUID.randomUUID();
        try {
            transcripts.start(id);
            currentSessionId = id;
        } catch (IOException e) {
            System.err.println("Warning: could not create session folder: " + e.getMessage());
            currentSessionId = null;
        }
        io.writeLine("Session: " + id);
    }

    private void appendUser(String content) {
        if (currentSessionId == null) {
            return;
        }
        try {
            transcripts.appendUser(currentSessionId, content);
        } catch (IOException e) {
            System.err.println("Warning: could not write session transcript: " + e.getMessage());
            currentSessionId = null;
        }
    }

    private void appendAssistant(String content, String thinking, Usage usage, boolean estimated) {
        if (currentSessionId == null) {
            return;
        }
        try {
            transcripts.appendAssistant(currentSessionId, content, thinking, usage, estimated);
        } catch (IOException e) {
            System.err.println("Warning: could not write session transcript: " + e.getMessage());
            currentSessionId = null;
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
                startNewSession();
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

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=ChatSessionTest`
Expected: PASS — 24 tests, `BUILD SUCCESS`.

- [ ] **Step 5: Run the full suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` — all tests green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ChatSession.java src/test/java/com/mrsmith/chat/ChatSessionTest.java
git commit -m "feat: per-session UUID and transcript logging in ChatSession"
```

---

### Task 5: ChatCommand Wiring

**Files:**
- Modify: `src/main/java/com/mrsmith/cli/ChatCommand.java`
- Modify: `src/test/java/com/mrsmith/cli/ChatCommandTest.java`

- [ ] **Step 1: Write the failing test** — in `ChatCommandTest.java`, extend the `helpExitsZeroAndPrintsUsage` test's assertions:

```java
            assertTrue(out.toString().contains("--model"));
            assertTrue(out.toString().contains("--sessions-dir"));
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ChatCommandTest`
Expected: FAIL — the help output does not yet contain `--sessions-dir`.

- [ ] **Step 3: Modify `ChatCommand.java`**

Add the imports:

```java
import com.mrsmith.session.FileTranscriptWriter;
import com.mrsmith.session.TranscriptWriter;
```

Add the `--sessions-dir` option (after `--include-usage`):

```java
    @Option(names = "--sessions-dir", description = "Directory where session transcripts are stored (overrides config file and env).")
    private Path sessionsDir;
```

(Add the import `import java.nio.file.Path;`.)

Change the `CliConfig` construction to include sessionsDir:

```java
            config = ConfigLoader.load(
                    new CliConfig(apiKey, baseUrl, model, systemPrompt, maxContext, includeUsage, sessionsDir));
```

And replace the session construction:

```java
        TranscriptWriter transcripts = new FileTranscriptWriter(config.sessionsDir());
        ChatSession session = new ChatSession(provider, io, config, transcripts);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=ChatCommandTest`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 5: Run the full suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` — all tests green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/cli/ChatCommand.java src/test/java/com/mrsmith/cli/ChatCommandTest.java
git commit -m "feat: wire session transcripts through ChatCommand"
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
Expected: usage shows `--sessions-dir`; exit code 0.

- [ ] **Step 4: Manual smoke test (user)**

With your real config at `~/.config/mrsmith/config.json`:
1. Run the CLI and confirm the banner prints `Session: <uuid>`.
2. Send a couple of messages (include one that produces thinking).
3. Confirm `~/.config/mrsmith/sessions/<uuid>/transcript.jsonl` exists and contains one JSON line per interaction, with `type`, `content`, `thinking` (when present), usage, and `timestamp`.
4. Run `/reset` and confirm a NEW `Session: <uuid>` appears and a new folder is created.
5. Run with `--sessions-dir /tmp/my-sessions` and confirm the folder is created there instead.
