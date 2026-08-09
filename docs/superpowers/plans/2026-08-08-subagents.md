# Sub-Agents (task tool) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `task` built-in tool that dispatches an isolated sub-agent (fresh context, its own nested tool loop minus `task`, same approval prompts) and returns its final answer, with optional `agent` selection and `task_id` resume, sub-agent transcripts persisted as `subagent-<n>.jsonl`, and a per-agent configurable tool round limit.

**Architecture:** Extract the main tool loop into a shared `ToolLoop`. Add `TaskRunner`/`TaskResult` and a `SubAgentRunner` that runs a nested loop against a fresh `FullContextBuilder`, writes sub-agent transcripts to flat files in the session folder, and accumulates usage in the main tracker. `TaskTool` delegates to the runner. The round limit becomes `AgentConfig.maxToolRounds` (default 8). `ToolRegistry`/factory gain a `TaskRunner` parameter.

**Tech Stack:** Java 21, JUnit 5 (Jupiter), Jackson, Maven, picocli.

---

## File Structure

**Create (main):**
- `src/main/java/com/mrsmith/chat/ToolLoop.java`
- `src/main/java/com/mrsmith/tool/TaskRunner.java`
- `src/main/java/com/mrsmith/tool/TaskResult.java`
- `src/main/java/com/mrsmith/tool/TaskTool.java`
- `src/main/java/com/mrsmith/chat/SubAgentRunner.java`
- `src/main/java/com/mrsmith/session/TranscriptJson.java`
- `src/main/java/com/mrsmith/session/SubAgentTranscriptWriter.java`
- `src/main/java/com/mrsmith/session/SubAgentTranscriptStore.java`

**Modify (main):**
- `src/main/java/com/mrsmith/config/AgentConfig.java` — add `Integer maxToolRounds`
- `src/main/java/com/mrsmith/config/AppConfig.java` — add `Integer maxToolRounds`
- `src/main/java/com/mrsmith/config/ConfigLoader.java` — parse `maxToolRounds`
- `src/main/java/com/mrsmith/config/AgentCatalog.java` — validate + resolve `maxToolRounds`
- `src/main/java/com/mrsmith/chat/ChatSession.java` — use `ToolLoop`, `maxToolRounds()`, build/reset `SubAgentRunner`
- `src/main/java/com/mrsmith/chat/UsageTracker.java` — add `recordSessionUsage`
- `src/main/java/com/mrsmith/session/FileTranscriptWriter.java` — delegate to `TranscriptJson`
- `src/main/java/com/mrsmith/tool/ToolRegistry.java` — `with(..., TaskRunner)`; always add `TaskTool` when runner non-null
- `src/main/java/com/mrsmith/tool/ToolRegistryFactory.java` — `create(..., TaskRunner)`
- `src/main/java/com/mrsmith/cli/ChatCommand.java` — 4-arg factory lambda

**Create (test):**
- `src/test/java/com/mrsmith/tool/TaskToolTest.java`
- `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java`
- `src/test/java/com/mrsmith/session/SubAgentTranscriptStoreTest.java`

**Modify (test):**
- `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`
- `src/test/java/com/mrsmith/config/AgentCatalogTest.java`
- `src/test/java/com/mrsmith/chat/ChatSessionTest.java`
- `src/test/java/com/mrsmith/tool/ToolRegistryTest.java`

---

### Task 1: Configurable tool round limit (`maxToolRounds`)

**Files:**
- Modify: `src/main/java/com/mrsmith/config/AgentConfig.java`
- Modify: `src/main/java/com/mrsmith/config/AppConfig.java`
- Modify: `src/main/java/com/mrsmith/config/ConfigLoader.java`
- Modify: `src/main/java/com/mrsmith/config/AgentCatalog.java`
- Modify: `src/main/java/com/mrsmith/chat/ChatSession.java`
- Modify: `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`
- Modify: `src/test/java/com/mrsmith/config/AgentCatalogTest.java`
- Modify: `src/test/java/com/mrsmith/chat/ChatSessionTest.java`

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`:

```java
    @Test
    void parsesMaxToolRoundsPerAgent() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m", "maxToolRounds": 12 } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(12, catalog.resolve("a").maxToolRounds());
    }

    @Test
    void maxToolRoundsDefaultsToNull() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(null, catalog.resolve("a").maxToolRounds());
    }
```

Append to `src/test/java/com/mrsmith/config/AgentCatalogTest.java`:

```java
    @Test
    void nonPositiveMaxToolRoundsThrows() {
        AgentConfig bad = new AgentConfig("coder", "opencode", "model-x", null, null, 0);
        assertThrows(ConfigException.class,
                () -> new AgentCatalog(List.of(provider), List.of(bad), "coder", true, Path.of("/tmp/s")));
    }

    @Test
    void resolveCarriesMaxToolRounds() {
        AgentConfig withRounds = new AgentConfig("coder", "opencode", "model-x", null, null, 5);
        AgentCatalog catalog = new AgentCatalog(List.of(provider), List.of(withRounds), "coder", true, Path.of("/tmp/s"));
        assertEquals(5, catalog.resolve("coder").maxToolRounds());
    }
```

Append to `src/test/java/com/mrsmith/chat/ChatSessionTest.java`:

```java
    @Test
    void toolRoundLimitHonorsConfig() throws Exception {
        FakeTool tool = new FakeTool("read_file", true, new ToolResult("data", false));
        ToolRegistryFactory registryFactory = (config, catalog, io) -> new ToolRegistry(List.of(tool));
        FakeToolProvider provider = new FakeToolProvider();
        provider.alwaysCall("read_file", JSON.readTree("{\"path\":\"a.txt\"}"));
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        AgentCatalog catalog = new AgentCatalog(
                List.of(new ProviderConfig("p", "sk-test", "https://example.com/v1")),
                List.of(new AgentConfig("a", "p", "m", null, null, 2)),
                "a", true, Path.of("sessions"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog, new FakeProviderFactory(provider), registryFactory, emptySkills(), "a");
        session.run();
        assertEquals(4, provider.calls);
        List<ChatMessage> lastSend = provider.receivedHistories.get(provider.receivedHistories.size() - 1);
        assertTrue(lastSend.get(lastSend.size() - 1).content().contains("round limit (2)"));
    }
```

Note: `new AgentConfig("a", "p", "m", null, null, 2)` uses the new 6-arg convenience constructor `(name, provider, model, systemPrompt, maxContextTokens, Integer maxToolRounds)`. The expected call count is L+2 (L tool rounds + the limit-detection send + the final limit-injection send): limit 2 → 4 calls (the default-8 case yields 10, matching the existing `stopsAtToolRoundLimit` test).

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=ConfigLoaderTest,AgentCatalogTest,ChatSessionTest test`
Expected: BUILD FAILURE — `maxToolRounds()` doesn't exist on `AppConfig`/`AgentConfig` and the 6-arg `AgentConfig` constructor doesn't match.

- [ ] **Step 3: Update AgentConfig**

Replace the contents of `src/main/java/com/mrsmith/config/AgentConfig.java` with:

```java
package com.mrsmith.config;

import java.util.List;

public record AgentConfig(String name, String provider, String model,
                          String systemPrompt, Integer maxContextTokens,
                          Integer maxToolRounds, List<String> tools) {

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens) {
        this(name, provider, model, systemPrompt, maxContextTokens, null, List.of());
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, List<String> tools) {
        this(name, provider, model, systemPrompt, maxContextTokens, null, tools);
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, Integer maxToolRounds) {
        this(name, provider, model, systemPrompt, maxContextTokens, maxToolRounds, List.of());
    }
}
```

- [ ] **Step 4: Update AppConfig**

Replace the contents of `src/main/java/com/mrsmith/config/AppConfig.java` with:

```java
package com.mrsmith.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record AppConfig(String apiKey, String baseUrl, String model, String systemPrompt,
                        Integer maxContextTokens, boolean includeUsage, Path sessionsDir,
                        List<String> tools, Integer maxToolRounds) {

    public AppConfig {
        Objects.requireNonNull(apiKey, "apiKey is required");
        Objects.requireNonNull(baseUrl, "baseUrl is required");
        Objects.requireNonNull(model, "model is required");
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public AppConfig(String apiKey, String baseUrl, String model, String systemPrompt) {
        this(apiKey, baseUrl, model, systemPrompt, null, true, null, List.of(), null);
    }

    public AppConfig(String apiKey, String baseUrl, String model, String systemPrompt,
                     Integer maxContextTokens, boolean includeUsage) {
        this(apiKey, baseUrl, model, systemPrompt, maxContextTokens, includeUsage, null, List.of(), null);
    }

    public AppConfig(String apiKey, String baseUrl, String model, String systemPrompt,
                     Integer maxContextTokens, boolean includeUsage, Path sessionsDir) {
        this(apiKey, baseUrl, model, systemPrompt, maxContextTokens, includeUsage, sessionsDir, List.of(), null);
    }
}
```

- [ ] **Step 5: Update ConfigLoader**

In `src/main/java/com/mrsmith/config/ConfigLoader.java`, change the `AgentConfig` construction in `parseAgents` to add:

```java
                        node.hasNonNull("maxToolRounds") ? node.get("maxToolRounds").asInt() : null,
```

so the construction becomes:

```java
                result.add(new AgentConfig(
                        node.path("name").asText(),
                        node.path("provider").asText(),
                        node.path("model").asText(null),
                        node.path("systemPrompt").asText(null),
                        node.hasNonNull("maxContextTokens") ? node.get("maxContextTokens").asInt() : null,
                        node.hasNonNull("maxToolRounds") ? node.get("maxToolRounds").asInt() : null,
                        parseTools(node)));
```

- [ ] **Step 6: Update AgentCatalog**

In `src/main/java/com/mrsmith/config/AgentCatalog.java`, inside the agent validation loop (after the `model` check), add:

```java
            if (agent.maxToolRounds() != null && agent.maxToolRounds() <= 0) {
                throw new ConfigException("Agent '" + agent.name() + "' must have a positive maxToolRounds.");
            }
```

And in `resolve(...)`, pass the value through:

```java
        return new AppConfig(provider.apiKey(), provider.baseUrl(), agent.model(),
                agent.systemPrompt(), agent.maxContextTokens(), includeUsage, sessionsDir, agent.tools(),
                agent.maxToolRounds());
```

- [ ] **Step 7: Update ChatSession**

In `src/main/java/com/mrsmith/chat/ChatSession.java`:

1. Rename the constant and add the helper. Replace:
```java
    private static final int MAX_TOOL_ROUNDS = 8;
```
with:
```java
    private static final int DEFAULT_MAX_TOOL_ROUNDS = 8;
```

2. In `runToolLoop()`, replace the two uses of `MAX_TOOL_ROUNDS` with `maxToolRounds()`:
```java
            if (round >= maxToolRounds()) {
                String limitContent = "Tool round limit (" + maxToolRounds() + ") reached; answer without more tool calls.";
```

3. Add the helper method (near `runToolLoop`):
```java
    private int maxToolRounds() {
        Integer value = config.maxToolRounds();
        return value == null ? DEFAULT_MAX_TOOL_ROUNDS : value;
    }
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `mvn -q -Dtest=ConfigLoaderTest,AgentCatalogTest,ChatSessionTest test`
Expected: BUILD SUCCESS (new tests pass; all existing pass — the default-8 behavior is unchanged).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/mrsmith/config/AgentConfig.java src/main/java/com/mrsmith/config/AppConfig.java src/main/java/com/mrsmith/config/ConfigLoader.java src/main/java/com/mrsmith/config/AgentCatalog.java src/main/java/com/mrsmith/chat/ChatSession.java src/test/java/com/mrsmith/config/ConfigLoaderTest.java src/test/java/com/mrsmith/config/AgentCatalogTest.java src/test/java/com/mrsmith/chat/ChatSessionTest.java
git commit -m "feat: make the tool round limit a per-agent config setting"
```

---

### Task 2: Extract the shared ToolLoop

Moves the tool-loop logic out of `ChatSession` into a reusable `ToolLoop` so the sub-agent runner (Task 4) can run the same loop. The main loop's behavior must be preserved exactly — the existing `ChatSessionTest` tool-loop tests are the regression guard.

**Files:**
- Create: `src/main/java/com/mrsmith/chat/ToolLoop.java`
- Modify: `src/main/java/com/mrsmith/chat/ChatSession.java`

- [ ] **Step 1: Create ToolLoop**

Create `src/main/java/com/mrsmith/chat/ToolLoop.java`:

```java
package com.mrsmith.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderResponse;
import com.mrsmith.provider.ToolCall;
import com.mrsmith.provider.Usage;
import com.mrsmith.tool.Tool;
import com.mrsmith.tool.ToolException;
import com.mrsmith.tool.ToolResult;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public final class ToolLoop {

    public static final int DEFAULT_MAX_TOOL_ROUNDS = 8;

    public interface Sink {
        void assistantWithToolCalls(ChatMessage message, List<ToolCall> calls);

        void toolResult(String id, String content, boolean error);
    }

    public record LoopResult(ChatMessage message, Usage usage, boolean estimated) {
    }

    private ToolLoop() {
    }

    public static LoopResult run(ContextBuilder context, Provider provider, List<Tool> tools,
                                 IO io, int maxToolRounds, Sink sink) {
        Accumulator acc = new Accumulator();
        for (int round = 0; ; round++) {
            ProviderResponse response = provider.send(context.messages(), tools, io::write, io::writeReasoning);
            accumulate(acc, response);
            ChatMessage message = response.message();
            List<ToolCall> calls = message.toolCalls();
            if (calls == null || calls.isEmpty()) {
                return new LoopResult(message, new Usage(acc.prompt, acc.completion), acc.estimated);
            }
            sink.assistantWithToolCalls(message, calls);
            if (round >= maxToolRounds) {
                String limitContent = "Tool round limit (" + maxToolRounds + ") reached; answer without more tool calls.";
                for (ToolCall call : calls) {
                    sink.toolResult(call.id(), limitContent, false);
                }
                ProviderResponse finalResponse = provider.send(context.messages(), tools, io::write, io::writeReasoning);
                accumulate(acc, finalResponse);
                return new LoopResult(finalResponse.message(), new Usage(acc.prompt, acc.completion), acc.estimated);
            }
            for (ToolCall call : calls) {
                ToolResult result = executeTool(call, tools, io);
                io.writeLine("tool: " + call.name() + "(" + describe(call) + ") -> "
                        + (result.error() ? "error" : "ok"));
                sink.toolResult(call.id(), result.content(), result.error());
            }
        }
    }

    private static void accumulate(Accumulator acc, ProviderResponse response) {
        acc.prompt += tokens(response.usage().promptTokens());
        acc.completion += tokens(response.usage().completionTokens());
        acc.estimated = acc.estimated || response.usageEstimated();
    }

    private static int tokens(Integer value) {
        return value == null ? 0 : value;
    }

    private static ToolResult executeTool(ToolCall call, List<Tool> tools, IO io) {
        Optional<Tool> found = find(tools, call.name());
        if (found.isEmpty()) {
            return new ToolResult("Unknown tool: " + call.name(), true);
        }
        Tool tool = found.get();
        if (!tool.isReadOnly() && !confirm(call, tool, io)) {
            return new ToolResult("User declined to run " + call.name() + ".", true);
        }
        try {
            return tool.execute(call.arguments());
        } catch (ToolException e) {
            return new ToolResult(e.getMessage(), true);
        }
    }

    private static Optional<Tool> find(List<Tool> tools, String name) {
        for (Tool tool : tools) {
            if (tool.name().equals(name)) {
                return Optional.of(tool);
            }
        }
        return Optional.empty();
    }

    private static boolean confirm(ToolCall call, Tool tool, IO io) {
        io.write("Run " + tool.name() + "(" + describe(call) + ") [y/N]? ");
        String answer;
        try {
            answer = io.readLine();
        } catch (IOException e) {
            return false;
        }
        return answer != null && (answer.trim().equalsIgnoreCase("y")
                || answer.trim().equalsIgnoreCase("yes"));
    }

    private static String describe(ToolCall call) {
        JsonNode args = call.arguments();
        for (String key : List.of("command", "path", "filePath", "pattern", "url")) {
            JsonNode value = args != null ? args.get(key) : null;
            if (value != null && value.isTextual()) {
                return value.asText();
            }
        }
        return "";
    }

    private static final class Accumulator {
        int prompt;
        int completion;
        boolean estimated;
    }
}
```

- [ ] **Step 2: Refactor ChatSession.runToolLoop**

In `src/main/java/com/mrsmith/chat/ChatSession.java`:

1. Replace the whole `runToolLoop()` method (and delete the now-unused private methods `recordSend`, `tokens`, `recordToolCallMessage`, `appendToolResultMessage`, `executeTool`, `confirm`, `statusLine`, `describe`, and the nested `Accumulator` class) with:

```java
    private TurnResult runToolLoop() {
        ToolLoop.LoopResult result = ToolLoop.run(contextBuilder, provider, toolRegistry.tools(),
                io, maxToolRounds(), new ToolLoop.Sink() {
                    @Override
                    public void assistantWithToolCalls(ChatMessage message, List<ToolCall> calls) {
                        history.add(message);
                        contextBuilder.appendAssistantToolCalls(calls);
                        for (ToolCall call : calls) {
                            appendToolCall(call);
                        }
                    }

                    @Override
                    public void toolResult(String id, String content, boolean error) {
                        history.add(new ChatMessage(Role.TOOL, content, null, null, id));
                        contextBuilder.appendToolResult(id, content);
                        appendToolResult(id, content, error);
                    }
                });
        return new TurnResult(result.message(), result.usage(), result.estimated);
    }
```

2. Remove the `DEFAULT_MAX_TOOL_ROUNDS` constant from ChatSession and have `maxToolRounds()` use `ToolLoop.DEFAULT_MAX_TOOL_ROUNDS`:
```java
    private int maxToolRounds() {
        Integer value = config.maxToolRounds();
        return value == null ? ToolLoop.DEFAULT_MAX_TOOL_ROUNDS : value;
    }
```

3. Remove now-unused imports (`JsonNode`, `Tool`, `ToolException`, `ToolResult`) if the compiler reports them unused — `ChatMessage`, `Role`, `ToolCall`, `ToolRegistry`, `Usage` are still used.

- [ ] **Step 3: Run the ChatSession tests**

Run: `mvn -q -Dtest=ChatSessionTest test`
Expected: BUILD SUCCESS — all existing tool-loop tests (approval, round limit, unknown tool, tool result feedback) pass unchanged.

Run the full suite: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: BUILD SUCCESS (the current count +0; ~252 tests).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ToolLoop.java src/main/java/com/mrsmith/chat/ChatSession.java
git commit -m "refactor: extract shared ToolLoop for main and sub-agent loops"
```

---

### Task 3: Shared transcript JSON + sub-agent transcript writer/store

**Files:**
- Create: `src/main/java/com/mrsmith/session/TranscriptJson.java`
- Create: `src/main/java/com/mrsmith/session/SubAgentTranscriptWriter.java`
- Create: `src/main/java/com/mrsmith/session/SubAgentTranscriptStore.java`
- Modify: `src/main/java/com/mrsmith/session/FileTranscriptWriter.java`
- Create: `src/test/java/com/mrsmith/session/SubAgentTranscriptStoreTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mrsmith/session/SubAgentTranscriptStoreTest.java`:

```java
package com.mrsmith.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Role;
import com.mrsmith.provider.ToolCall;
import com.mrsmith.provider.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubAgentTranscriptStoreTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private final UUID sessionId = UUID.randomUUID();

    private SubAgentTranscriptStore store() {
        return new SubAgentTranscriptStore(tempDir, () -> sessionId);
    }

    @Test
    void writesAndReadsRecordsRoundTrip() throws IOException {
        Files.createDirectories(tempDir.resolve(sessionId.toString()));
        SubAgentTranscriptStore store = store();
        TranscriptWriter writer = store.writer(1);
        writer.start(sessionId);
        writer.appendUser(sessionId, "do the thing");
        writer.appendAssistant(sessionId, "answer", "ponder", new Usage(100, 50), false);
        writer.appendToolCall(sessionId, "c1", "read_file", JSON.readTree("{\"path\":\"a.txt\"}"));
        writer.appendToolResult(sessionId, "c1", "file contents", false);

        Path file = tempDir.resolve(sessionId.toString()).resolve("subagent-1.jsonl");
        assertTrue(Files.isRegularFile(file));
        List<String> lines = Files.readAllLines(file);
        assertEquals(4, lines.size());

        List<ChatMessage> messages = store.read(1);
        assertEquals(4, messages.size());
        assertEquals(Role.USER, messages.get(0).role());
        assertEquals("do the thing", messages.get(0).content());
        assertEquals(Role.ASSISTANT, messages.get(1).role());
        assertEquals("answer", messages.get(1).content());
        assertEquals(Role.ASSISTANT, messages.get(2).role());
        assertEquals(1, messages.get(2).toolCalls().size());
        assertEquals("c1", messages.get(2).toolCalls().get(0).id());
        assertEquals(Role.TOOL, messages.get(3).role());
        assertEquals("c1", messages.get(3).toolCallId());
        assertEquals("file contents", messages.get(3).content());
    }

    @Test
    void missingFileReadsAsNull() {
        assertNull(store().read(7));
    }

    @Test
    void fileNamesAreSequentialPerSession() {
        assertEquals("subagent-1.jsonl", store().file(1).getFileName().toString());
        assertEquals("subagent-2.jsonl", store().file(2).getFileName().toString());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=SubAgentTranscriptStoreTest test`
Expected: BUILD FAILURE — `cannot find symbol: class SubAgentTranscriptStore`

- [ ] **Step 3: Create TranscriptJson**

Create `src/main/java/com/mrsmith/session/TranscriptJson.java`:

```java
package com.mrsmith.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.provider.Usage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

final class TranscriptJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TranscriptJson() {
    }

    static void append(Path file, ObjectNode record) throws IOException {
        String line = JSON.writeValueAsString(record);
        Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static ObjectNode user(String content) {
        ObjectNode record = JSON.createObjectNode();
        record.put("type", "user");
        record.put("content", content);
        record.put("timestamp", Instant.now().toString());
        return record;
    }

    static ObjectNode assistant(String content, String thinking, Usage usage, boolean estimated) {
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
        return record;
    }

    static ObjectNode toolCall(String id, String name, JsonNode arguments) {
        ObjectNode record = JSON.createObjectNode();
        record.put("type", "tool_call");
        record.put("id", id);
        record.put("name", name);
        if (arguments != null) {
            record.set("arguments", arguments);
        }
        record.put("timestamp", Instant.now().toString());
        return record;
    }

    static ObjectNode toolResult(String id, String content, boolean error) {
        ObjectNode record = JSON.createObjectNode();
        record.put("type", "tool_result");
        record.put("id", id);
        record.put("content", content);
        record.put("error", error);
        record.put("timestamp", Instant.now().toString());
        return record;
    }

    static ObjectNode skillLoad(String name) {
        ObjectNode record = JSON.createObjectNode();
        record.put("type", "skill_load");
        record.put("name", name);
        record.put("timestamp", Instant.now().toString());
        return record;
    }
}
```

- [ ] **Step 4: Create SubAgentTranscriptWriter**

Create `src/main/java/com/mrsmith/session/SubAgentTranscriptWriter.java`:

```java
package com.mrsmith.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.mrsmith.provider.Usage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class SubAgentTranscriptWriter implements TranscriptWriter {

    private final Path file;

    public SubAgentTranscriptWriter(Path file) {
        this.file = file;
    }

    @Override
    public void start(UUID sessionId) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    @Override
    public void appendUser(UUID sessionId, String content) throws IOException {
        TranscriptJson.append(file, TranscriptJson.user(content));
    }

    @Override
    public void appendAssistant(UUID sessionId, String content, String thinking,
                                Usage usage, boolean estimated) throws IOException {
        TranscriptJson.append(file, TranscriptJson.assistant(content, thinking, usage, estimated));
    }

    @Override
    public void appendToolCall(UUID sessionId, String id, String name, JsonNode arguments) throws IOException {
        TranscriptJson.append(file, TranscriptJson.toolCall(id, name, arguments));
    }

    @Override
    public void appendToolResult(UUID sessionId, String id, String content, boolean error) throws IOException {
        TranscriptJson.append(file, TranscriptJson.toolResult(id, content, error));
    }

    @Override
    public void appendSkillLoad(UUID sessionId, String name) throws IOException {
        TranscriptJson.append(file, TranscriptJson.skillLoad(name));
    }
}
```

- [ ] **Step 5: Create SubAgentTranscriptStore**

Create `src/main/java/com/mrsmith/session/SubAgentTranscriptStore.java`:

```java
package com.mrsmith.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Role;
import com.mrsmith.provider.ToolCall;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public final class SubAgentTranscriptStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path sessionsRoot;
    private final Supplier<UUID> sessionId;

    public SubAgentTranscriptStore(Path sessionsRoot, Supplier<UUID> sessionId) {
        this.sessionsRoot = sessionsRoot;
        this.sessionId = sessionId;
    }

    public Path file(int n) {
        return sessionsRoot.resolve(sessionId.get().toString()).resolve("subagent-" + n + ".jsonl");
    }

    public TranscriptWriter writer(int n) {
        return new SubAgentTranscriptWriter(file(n));
    }

    public List<ChatMessage> read(int n) throws IOException {
        Path path = file(n);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        List<ChatMessage> messages = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            JsonNode record = JSON.readTree(line);
            switch (record.path("type").asText()) {
                case "user" -> messages.add(new ChatMessage(Role.USER, record.path("content").asText()));
                case "assistant" -> messages.add(new ChatMessage(Role.ASSISTANT,
                        record.path("content").asText(),
                        record.has("thinking") ? record.path("thinking").asText() : null));
                case "tool_call" -> messages.add(new ChatMessage(Role.ASSISTANT, null, null,
                        List.of(new ToolCall(record.path("id").asText(), record.path("name").asText(),
                                record.has("arguments") ? record.get("arguments") : null)), null));
                case "tool_result" -> messages.add(new ChatMessage(Role.TOOL,
                        record.path("content").asText(), null, null, record.path("id").asText()));
                default -> {
                    // skill_load and unknown records are skipped on replay
                }
            }
        }
        return messages;
    }
}
```

- [ ] **Step 6: Refactor FileTranscriptWriter to delegate**

Replace the contents of `src/main/java/com/mrsmith/session/FileTranscriptWriter.java` with:

```java
package com.mrsmith.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.mrsmith.provider.Usage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class FileTranscriptWriter implements TranscriptWriter {

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
        TranscriptJson.append(transcriptFile(sessionId), TranscriptJson.user(content));
    }

    @Override
    public void appendAssistant(UUID sessionId, String content, String thinking,
                                Usage usage, boolean estimated) throws IOException {
        TranscriptJson.append(transcriptFile(sessionId),
                TranscriptJson.assistant(content, thinking, usage, estimated));
    }

    @Override
    public void appendToolCall(UUID sessionId, String id, String name, JsonNode arguments) throws IOException {
        TranscriptJson.append(transcriptFile(sessionId), TranscriptJson.toolCall(id, name, arguments));
    }

    @Override
    public void appendToolResult(UUID sessionId, String id, String content, boolean error) throws IOException {
        TranscriptJson.append(transcriptFile(sessionId), TranscriptJson.toolResult(id, content, error));
    }

    @Override
    public void appendSkillLoad(UUID sessionId, String name) throws IOException {
        TranscriptJson.append(transcriptFile(sessionId), TranscriptJson.skillLoad(name));
    }

    private Path folder(UUID sessionId) {
        return sessionsRoot.resolve(sessionId.toString());
    }

    private Path transcriptFile(UUID sessionId) {
        return folder(sessionId).resolve("transcript.jsonl");
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn -q -Dtest=SubAgentTranscriptStoreTest,FileTranscriptWriterTest test`
Expected: BUILD SUCCESS (new store test + existing file-writer tests, which verify the refactor didn't change record shapes).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/mrsmith/session/TranscriptJson.java src/main/java/com/mrsmith/session/SubAgentTranscriptWriter.java src/main/java/com/mrsmith/session/SubAgentTranscriptStore.java src/main/java/com/mrsmith/session/FileTranscriptWriter.java src/test/java/com/mrsmith/session/SubAgentTranscriptStoreTest.java
git commit -m "feat: add sub-agent transcript store and shared transcript JSON"
```

---

### Task 4: TaskRunner + SubAgentRunner + session usage recording

**Files:**
- Create: `src/main/java/com/mrsmith/tool/TaskRunner.java`
- Create: `src/main/java/com/mrsmith/tool/TaskResult.java`
- Create: `src/main/java/com/mrsmith/chat/SubAgentRunner.java`
- Modify: `src/main/java/com/mrsmith/chat/UsageTracker.java`
- Create: `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java`:

```java
package com.mrsmith.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrsmith.config.AgentCatalog;
import com.mrsmith.config.AgentConfig;
import com.mrsmith.config.ProviderConfig;
import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderFactory;
import com.mrsmith.provider.ProviderResponse;
import com.mrsmith.provider.Role;
import com.mrsmith.provider.ToolCall;
import com.mrsmith.provider.Usage;
import com.mrsmith.tool.TaskResult;
import com.mrsmith.tool.Tool;
import com.mrsmith.tool.ToolRegistry;
import com.mrsmith.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubAgentRunnerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private final UUID sessionId = UUID.randomUUID();

    private AgentCatalog catalog() {
        return new AgentCatalog(
                List.of(new ProviderConfig("p", "sk-test", "https://example.com/v1")),
                List.of(new AgentConfig("a", "p", "m", "you are a", null)),
                "a", true, Path.of("sessions"));
    }

    private SubAgentRunner runner(Provider provider, ToolRegistry tools, IO io) throws IOException {
        Files.createDirectories(tempDir.resolve(sessionId.toString()));
        AgentCatalog catalog = catalog();
        ProviderFactory factory = new FakeProviderFactory(provider);
        UsageTracker tracker = new UsageTracker();
        return new SubAgentRunner(catalog, factory, cfg -> tools, io, tracker,
                () -> catalog.resolve("a"), () -> sessionId);
    }

    private List<String> readSubAgentFile(int n) throws IOException {
        return Files.readAllLines(tempDir.resolve(sessionId.toString()).resolve("subagent-" + n + ".jsonl"));
    }

    static class StubIo implements IO {
        final Deque<String> inputs;
        final List<String> lines = new ArrayList<>();

        StubIo(List<String> inputs) {
            this.inputs = new ArrayDeque<>(inputs);
        }

        @Override
        public String readLine() throws IOException {
            return inputs.poll();
        }

        @Override
        public void write(String text) {
            lines.add(text);
        }

        @Override
        public void writeLine(String line) {
            lines.add(line);
        }

        @Override
        public void writeReasoning(String text) {
            lines.add(text);
        }
    }

    static class FakeProvider implements Provider {
        final List<ToolCall> plannedCalls;
        final List<List<ChatMessage>> receivedHistories = new ArrayList<>();
        int calls = 0;

        FakeProvider(ToolCall... plannedCalls) {
            this.plannedCalls = List.of(plannedCalls);
        }

        @Override
        public ProviderResponse send(List<ChatMessage> history, List<Tool> tools, Consumer<String> tokenSink,
                                     Consumer<String> reasoningSink) {
            receivedHistories.add(new ArrayList<>(history));
            calls++;
            if (calls <= plannedCalls.size()) {
                return new ProviderResponse(
                        new ChatMessage(Role.ASSISTANT, null, null, List.of(plannedCalls.get(calls - 1)), null),
                        new Usage(10, 5), false);
            }
            ChatMessage last = history.get(history.size() - 1);
            String reply = last.content() + " sub reply";
            tokenSink.accept(reply);
            return new ProviderResponse(new ChatMessage(Role.ASSISTANT, reply), new Usage(10, 5), false);
        }
    }

    static class FakeProviderFactory implements ProviderFactory {
        final Provider provider;

        FakeProviderFactory(Provider provider) {
            this.provider = provider;
        }

        @Override
        public Provider create(com.mrsmith.config.AppConfig config) {
            return provider;
        }
    }

    static class FakeTool implements Tool {
        final String name;
        final boolean readOnly;
        final ToolResult result;
        int calls = 0;

        FakeTool(String name, boolean readOnly, ToolResult result) {
            this.name = name;
            this.readOnly = readOnly;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public JsonNode parametersSchema() {
            return JSON.createObjectNode();
        }

        @Override
        public boolean isReadOnly() {
            return readOnly;
        }

        @Override
        public ToolResult execute(JsonNode args) {
            calls++;
            return result;
        }
    }

    @Test
    void freshRunReturnsFinalAnswerAndWritesTranscript() throws Exception {
        FakeProvider provider = new FakeProvider();
        ToolRegistry tools = new ToolRegistry(List.of());
        SubAgentRunner runner = runner(provider, tools, new StubIo(List.of()));
        TaskResult result = runner.run("do the thing", null, null);
        assertFalse(result.error());
        assertEquals("subagent-1", result.id());
        assertTrue(result.message().contains("do the thing"));
        List<String> lines = readSubAgentFile(1);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"type\":\"user\""));
        assertTrue(lines.get(1).contains("\"type\":\"assistant\""));
    }

    @Test
    void toolCallsAreRecordedInTranscript() throws Exception {
        FakeTool readFile = new FakeTool("read_file", true, new ToolResult("contents", false));
        ToolRegistry tools = new ToolRegistry(List.of(readFile));
        FakeProvider provider = new FakeProvider(
                new ToolCall("c1", "read_file", JSON.readTree("{\"path\":\"a.txt\"}")));
        SubAgentRunner runner = runner(provider, tools, new StubIo(List.of()));
        TaskResult result = runner.run("inspect", null, null);
        assertFalse(result.error());
        assertEquals(1, readFile.calls);
        List<String> lines = readSubAgentFile(1);
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"type\":\"tool_call\"")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"type\":\"tool_result\"")));
    }

    @Test
    void resumeReplaysPriorContextAndAppends() throws Exception {
        FakeProvider provider = new FakeProvider();
        ToolRegistry tools = new ToolRegistry(List.of());
        SubAgentRunner runner = runner(provider, tools, new StubIo(List.of()));
        runner.run("first", null, null);
        assertEquals(1, provider.receivedHistories.size());
        TaskResult resumed = runner.run("continue", null, "subagent-1");
        assertFalse(resumed.error());
        assertEquals("subagent-1", resumed.id());
        assertEquals(2, provider.receivedHistories.size());
        List<ChatMessage> secondContext = provider.receivedHistories.get(1);
        assertTrue(secondContext.stream().anyMatch(m -> m.role() == Role.SYSTEM && m.content().contains("you are a")));
        assertTrue(secondContext.stream().anyMatch(m -> m.role() == Role.USER && m.content().equals("first")));
        assertTrue(secondContext.stream().anyMatch(m -> m.role() == Role.USER && m.content().equals("continue")));
        assertEquals(4, readSubAgentFile(1).size());
    }

    @Test
    void unknownAgentReturnsError() {
        SubAgentRunner runner = runner(new FakeProvider(), new ToolRegistry(List.of()), new StubIo(List.of()));
        TaskResult result = runner.run("x", "nope", null);
        assertTrue(result.error());
        assertTrue(result.message().contains("Unknown agent"));
    }

    @Test
    void unknownTaskIdReturnsError() {
        SubAgentRunner runner = runner(new FakeProvider(), new ToolRegistry(List.of()), new StubIo(List.of()));
        TaskResult result = runner.run("x", null, "subagent-9");
        assertTrue(result.error());
        assertTrue(result.message().contains("Unknown task_id"));
    }

    @Test
    void resetRestartsSequentialNumbering() throws Exception {
        SubAgentRunner runner = runner(new FakeProvider(), new ToolRegistry(List.of()), new StubIo(List.of()));
        assertEquals("subagent-1", runner.run("a", null, null).id());
        assertEquals("subagent-2", runner.run("b", null, null).id());
        runner.reset();
        assertEquals("subagent-1", runner.run("c", null, null).id());
    }

    @Test
    void destructiveToolPromptsForApproval() throws Exception {
        FakeTool edit = new FakeTool("edit", false, new ToolResult("edited", false));
        ToolRegistry tools = new ToolRegistry(List.of(edit));
        FakeProvider provider = new FakeProvider(
                new ToolCall("c1", "edit", JSON.readTree("{\"filePath\":\"a.txt\",\"oldString\":\"x\",\"newString\":\"y\"}")));
        SubAgentRunner runner = runner(provider, tools, new StubIo(List.of("y")));
        TaskResult result = runner.run("edit it", null, null);
        assertFalse(result.error());
        assertEquals(1, edit.calls);
        List<String> lines = readSubAgentFile(1);
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"type\":\"tool_result\"")));
    }

    @Test
    void declinedDestructiveToolRecordsDecline() throws Exception {
        FakeTool edit = new FakeTool("edit", false, new ToolResult("edited", false));
        ToolRegistry tools = new ToolRegistry(List.of(edit));
        FakeProvider provider = new FakeProvider(
                new ToolCall("c1", "edit", JSON.readTree("{\"filePath\":\"a.txt\",\"oldString\":\"x\",\"newString\":\"y\"}")));
        SubAgentRunner runner = runner(provider, tools, new StubIo(List.of("n")));
        TaskResult result = runner.run("edit it", null, null);
        assertFalse(result.error());
        assertEquals(0, edit.calls);
        List<String> lines = readSubAgentFile(1);
        assertTrue(lines.stream().anyMatch(l -> l.contains("declined")));
    }

    @Test
    void subAgentUsageAccumulatesInSessionTracker() throws Exception {
        UsageTracker tracker = new UsageTracker();
        Files.createDirectories(tempDir.resolve(sessionId.toString()));
        AgentCatalog catalog = catalog();
        SubAgentRunner runner = new SubAgentRunner(catalog, new FakeProviderFactory(new FakeProvider()),
                cfg -> new ToolRegistry(List.of()), new StubIo(List.of()), tracker,
                () -> catalog.resolve("a"), () -> sessionId);
        runner.run("x", null, null);
        assertEquals(10, tracker.promptTokens());
        assertEquals(5, tracker.completionTokens());
        assertEquals(15, tracker.totalTokens());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=SubAgentRunnerTest test`
Expected: BUILD FAILURE — `cannot find symbol: class SubAgentRunner` / `TaskRunner` / `TaskResult`.

- [ ] **Step 3: Create TaskRunner and TaskResult**

Create `src/main/java/com/mrsmith/tool/TaskRunner.java`:

```java
package com.mrsmith.tool;

public interface TaskRunner {

    TaskResult run(String prompt, String agentName, String taskId);
}
```

Create `src/main/java/com/mrsmith/tool/TaskResult.java`:

```java
package com.mrsmith.tool;

public record TaskResult(String id, String message, boolean error) {
}
```

- [ ] **Step 4: Add UsageTracker.recordSessionUsage**

In `src/main/java/com/mrsmith/chat/UsageTracker.java`, refactor `recordTurn` and add `recordSessionUsage`:

```java
    public void recordTurn(Usage usage, boolean estimated) {
        if (usage == null) {
            return;
        }
        lastTurn = usage;
        lastTurnEstimated = estimated;
        accumulate(usage, estimated);
    }

    public void recordSessionUsage(Usage usage, boolean estimated) {
        if (usage == null) {
            return;
        }
        accumulate(usage, estimated);
    }

    private void accumulate(Usage usage, boolean estimated) {
        if (estimated) {
            sessionEstimated = true;
        }
        if (usage.promptTokens() != null) {
            promptTokens += usage.promptTokens();
        }
        if (usage.completionTokens() != null) {
            completionTokens += usage.completionTokens();
        }
    }
```

- [ ] **Step 5: Create SubAgentRunner**

Create `src/main/java/com/mrsmith/chat/SubAgentRunner.java`:

```java
package com.mrsmith.chat;

import com.mrsmith.config.AgentCatalog;
import com.mrsmith.config.AppConfig;
import com.mrsmith.config.ConfigException;
import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderException;
import com.mrsmith.provider.ProviderFactory;
import com.mrsmith.provider.ToolCall;
import com.mrsmith.skill.SkillCatalog;
import com.mrsmith.session.SubAgentTranscriptStore;
import com.mrsmith.session.TranscriptWriter;
import com.mrsmith.tool.Resettable;
import com.mrsmith.tool.TaskResult;
import com.mrsmith.tool.TaskRunner;
import com.mrsmith.tool.ToolRegistry;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

public final class SubAgentRunner implements TaskRunner, Resettable {

    private final AgentCatalog agents;
    private final ProviderFactory providerFactory;
    private final Function<AppConfig, ToolRegistry> toolsBuilder;
    private final IO io;
    private final UsageTracker tracker;
    private final Supplier<AppConfig> currentConfig;
    private final Supplier<UUID> sessionId;
    private final SubAgentTranscriptStore store;

    private int counter;

    public SubAgentRunner(AgentCatalog agents, ProviderFactory providerFactory,
                          Function<AppConfig, ToolRegistry> toolsBuilder, IO io,
                          UsageTracker tracker, Supplier<AppConfig> currentConfig,
                          Supplier<UUID> sessionId) {
        this.agents = agents;
        this.providerFactory = providerFactory;
        this.toolsBuilder = toolsBuilder;
        this.io = io;
        this.tracker = tracker;
        this.currentConfig = currentConfig;
        this.sessionId = sessionId;
        this.store = new SubAgentTranscriptStore(agents.sessionsDir(), sessionId);
    }

    @Override
    public void reset() {
        counter = 0;
    }

    @Override
    public TaskResult run(String prompt, String agentName, String taskId) {
        AppConfig config = resolveConfig(agentName);
        if (config == null) {
            return new TaskResult(null, "Unknown agent: " + agentName, true);
        }
        int n;
        boolean resume;
        if (taskId == null || taskId.isBlank()) {
            n = ++counter;
            resume = false;
        } else {
            Integer parsed = parseTaskId(taskId);
            if (parsed == null) {
                return new TaskResult(null, "Unknown task_id: " + taskId, true);
            }
            n = parsed;
            resume = true;
        }
        FullContextBuilder context = new FullContextBuilder();
        context.start(config.systemPrompt());
        List<ChatMessage> replayed;
        try {
            replayed = resume ? store.read(n) : List.of();
        } catch (IOException e) {
            return new TaskResult(null, "Unknown task_id: " + taskId, true);
        }
        if (replayed == null) {
            return new TaskResult(null, "Unknown task_id: " + taskId, true);
        }
        for (ChatMessage message : replayed) {
            replay(context, message);
        }
        context.appendUser(prompt);

        Provider provider = providerFactory.create(config);
        ToolRegistry tools = toolsBuilder.apply(config);
        TranscriptWriter transcripts = store.writer(n);
        try {
            if (sessionId.get() != null) {
                transcripts.start(sessionId.get());
                transcripts.appendUser(sessionId.get(), prompt);
            }
            ToolLoop.LoopResult result = ToolLoop.run(context, provider, tools.tools(),
                    io, maxToolRounds(config), sinkFor(context, transcripts));
            if (sessionId.get() != null) {
                transcripts.appendAssistant(sessionId.get(), result.message().content(),
                        result.message().thinking(), result.usage(), result.estimated);
            }
            tracker.recordSessionUsage(result.usage(), result.estimated);
            return new TaskResult("subagent-" + n, result.message().content(), false);
        } catch (ProviderException | RuntimeException e) {
            return new TaskResult("subagent-" + n, e.getMessage(), true);
        } catch (IOException e) {
            return new TaskResult("subagent-" + n, "could not write subagent transcript: " + e.getMessage(), true);
        }
    }

    private AppConfig resolveConfig(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return currentConfig.get();
        }
        try {
            return agents.resolve(agentName);
        } catch (ConfigException e) {
            return null;
        }
    }

    private ToolLoop.Sink sinkFor(FullContextBuilder context, TranscriptWriter transcripts) {
        return new ToolLoop.Sink() {
            @Override
            public void assistantWithToolCalls(ChatMessage message, List<ToolCall> calls) {
                context.appendAssistantToolCalls(calls);
                if (sessionId.get() != null) {
                    try {
                        for (ToolCall call : calls) {
                            transcripts.appendToolCall(sessionId.get(), call.id(), call.name(), call.arguments());
                        }
                    } catch (IOException e) {
                        System.err.println("Warning: could not write subagent transcript: " + e.getMessage());
                    }
                }
            }

            @Override
            public void toolResult(String id, String content, boolean error) {
                context.appendToolResult(id, content);
                if (sessionId.get() != null) {
                    try {
                        transcripts.appendToolResult(sessionId.get(), id, content, error);
                    } catch (IOException e) {
                        System.err.println("Warning: could not write subagent transcript: " + e.getMessage());
                    }
                }
            }
        };
    }

    private static void replay(FullContextBuilder context, ChatMessage message) {
        switch (message.role()) {
            case SYSTEM -> context.appendSystem(message.content());
            case USER -> context.appendUser(message.content());
            case ASSISTANT -> {
                if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    context.appendAssistantToolCalls(message.toolCalls());
                } else {
                    context.appendAssistant(message.content());
                }
            }
            case TOOL -> context.appendToolResult(message.toolCallId(), message.content());
        }
    }

    private static Integer parseTaskId(String taskId) {
        if (!taskId.startsWith("subagent-")) {
            return null;
        }
        String rest = taskId.substring("subagent-".length());
        if (!rest.matches("\\d{1,9}")) {
            return null;
        }
        return Integer.parseInt(rest);
    }

    private static int maxToolRounds(AppConfig config) {
        Integer value = config.maxToolRounds();
        return value == null ? ToolLoop.DEFAULT_MAX_TOOL_ROUNDS : value;
    }
}
```

Note: `SubAgentRunner` does NOT import `SkillCatalog` (the tools builder is injected).

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn -q -Dtest=SubAgentRunnerTest test`
Expected: BUILD SUCCESS (all 8 tests pass). If the `runner(...)` test helper leaves an unused `SkillCatalog` import reference, the SubAgentRunner imports `SkillCatalog` only if used — it is NOT used (the tools builder is injected), so omit that import.

- [ ] **Step 7: Run the full suite**

Run: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: BUILD SUCCESS (~260 tests).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/mrsmith/tool/TaskRunner.java src/main/java/com/mrsmith/tool/TaskResult.java src/main/java/com/mrsmith/chat/SubAgentRunner.java src/main/java/com/mrsmith/chat/UsageTracker.java src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java
git commit -m "feat: add sub-agent runner with nested tool loop and transcript persistence"
```

---

### Task 5: TaskTool + registry/factory wiring

**Files:**
- Create: `src/main/java/com/mrsmith/tool/TaskTool.java`
- Modify: `src/main/java/com/mrsmith/tool/ToolRegistry.java`
- Modify: `src/main/java/com/mrsmith/tool/ToolRegistryFactory.java`
- Modify: `src/main/java/com/mrsmith/cli/ChatCommand.java`
- Modify: `src/main/java/com/mrsmith/chat/ChatSession.java`
- Create: `src/test/java/com/mrsmith/tool/TaskToolTest.java`
- Modify: `src/test/java/com/mrsmith/tool/ToolRegistryTest.java`
- Modify: `src/test/java/com/mrsmith/chat/ChatSessionTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/mrsmith/tool/TaskToolTest.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void delegatesToRunnerAndFormatsResult() throws Exception {
        TaskRunner runner = (prompt, agent, taskId) -> new TaskResult("subagent-1", "all done", false);
        TaskTool tool = new TaskTool(runner);
        ToolResult result = tool.execute(JSON.readTree(
                "{\"description\":\"summarize\",\"prompt\":\"do the work\"}"));
        assertFalse(result.error());
        assertEquals("Subagent subagent-1: all done", result.content());
    }

    @Test
    void passesAgentAndTaskIdThrough() throws Exception {
        TaskRunner runner = (prompt, agent, taskId) ->
                new TaskResult("subagent-3", prompt + "/" + agent + "/" + taskId, false);
        TaskTool tool = new TaskTool(runner);
        ToolResult result = tool.execute(JSON.readTree(
                "{\"description\":\"x\",\"prompt\":\"p\",\"agent\":\"b\",\"task_id\":\"subagent-3\"}"));
        assertEquals("Subagent subagent-3: p/b/subagent-3", result.content());
    }

    @Test
    void runnerErrorBecomesErrorResult() throws Exception {
        TaskRunner runner = (prompt, agent, taskId) -> new TaskResult(null, "Unknown agent: nope", true);
        TaskTool tool = new TaskTool(runner);
        ToolResult result = tool.execute(JSON.readTree(
                "{\"description\":\"x\",\"prompt\":\"p\"}"));
        assertTrue(result.error());
        assertEquals("Unknown agent: nope", result.content());
    }

    @Test
    void missingArgumentsThrow() {
        TaskTool tool = new TaskTool((p, a, t) -> new TaskResult(null, "x", true));
        assertThrows(ToolException.class, () -> tool.execute(JSON.readTree("{}")));
    }

    @Test
    void nameIsTaskAndReadOnly() {
        TaskTool tool = new TaskTool((p, a, t) -> new TaskResult(null, "", true));
        assertEquals("task", tool.name());
        assertTrue(tool.isReadOnly());
    }
}
```

Append to `src/test/java/com/mrsmith/tool/ToolRegistryTest.java`:

```java
    private final TaskRunner taskRunner = (p, a, t) -> new TaskResult("subagent-1", "stub", false);

    @Test
    void taskToolAddedWhenRunnerProvided() {
        ToolRegistry withRunner = ToolRegistry.with(List.of(), emptyCatalog(), io, taskRunner);
        assertTrue(withRunner.find("task").isPresent());
        ToolRegistry withoutRunner = ToolRegistry.with(List.of(), emptyCatalog(), io, null);
        assertFalse(withoutRunner.find("task").isPresent());
        assertEquals(3, withoutRunner.tools().size());
        assertEquals(4, withRunner.tools().size());
    }

    @Test
    void taskToolNotInBuiltinNames() {
        assertFalse(ToolRegistry.builtinNames().contains("task"));
    }
```

(Update the existing `with(...)` calls in `ToolRegistryTest` to pass `taskRunner` as the 4th argument, and the existing tool-count assertions: `builtInWithNamesCreatesAllRequestedTools` → 10, `alwaysOnToolsAddedEvenWhenCatalogEmpty` → 4 and it should also assert `find("task")` present, `addsSkillToolWhenCatalogNonEmpty` → 5.)

Append to `src/test/java/com/mrsmith/chat/ChatSessionTest.java`:

```java
    @Test
    void taskToolResultFeedsBack() throws Exception {
        TaskRunner fakeRunner = (prompt, agent, taskId) -> new TaskResult("subagent-1", "all done", false);
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) ->
                ToolRegistry.with(List.of(), catalog, io, fakeRunner);
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_t", "task", JSON.readTree("{\"description\":\"x\",\"prompt\":\"do it\"}")),
                "answer");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        List<ChatMessage> secondSend = toolProvider.receivedHistories.get(1);
        ChatMessage last = secondSend.get(secondSend.size() - 1);
        assertEquals(Role.TOOL, last.role());
        assertEquals("Subagent subagent-1: all done", last.content());
    }

    @Test
    void toolsLessAgentGetsTaskTool() throws Exception {
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) ->
                ToolRegistry.with(List.of(), catalog, io, taskRunner);
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(provider), registryFactory, emptySkills(), "a");
        session.run();
        assertTrue(provider.receivedTools.get(0).stream().anyMatch(t -> t.name().equals("task")));
    }
```

(Add imports `com.mrsmith.tool.TaskRunner` and `com.mrsmith.tool.TaskResult` to `ChatSessionTest`.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=TaskToolTest test`
Expected: BUILD FAILURE — `cannot find symbol: class TaskTool`. (The `ToolRegistryTest`/`ChatSessionTest` additions fail to compile until the wiring changes land in Step 3.)

- [ ] **Step 3: Create TaskTool**

Create `src/main/java/com/mrsmith/tool/TaskTool.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class TaskTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TaskRunner runner;

    public TaskTool(TaskRunner runner) {
        this.runner = runner;
    }

    @Override
    public String name() {
        return "task";
    }

    @Override
    public String description() {
        return "Dispatch a sub-agent with an isolated context to work autonomously, "
                + "then return its final answer. Use for large or separable pieces of work; "
                + "do not duplicate the sub-agent's work yourself. Include the full task "
                + "description and the exact information you want back in its final message.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("description").put("type", "string");
        properties.putObject("prompt").put("type", "string");
        properties.putObject("agent").put("type", "string");
        properties.putObject("task_id").put("type", "string");
        schema.putArray("required").add("description").add("prompt");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String description = args.path("description").asText(null);
        String prompt = args.path("prompt").asText(null);
        if (description == null || description.isBlank() || prompt == null || prompt.isBlank()) {
            throw new ToolException("missing required 'description' and 'prompt' arguments");
        }
        String agent = args.path("agent").asText(null);
        String taskId = args.path("task_id").asText(null);
        TaskResult result = runner.run(prompt, agent, taskId);
        if (result.error()) {
            return new ToolResult(result.message(), true);
        }
        return new ToolResult("Subagent " + result.id() + ": " + result.message(), false);
    }
}
```

- [ ] **Step 4: Update ToolRegistry and ToolRegistryFactory**

In `src/main/java/com/mrsmith/tool/ToolRegistry.java`, change `with` to take a `TaskRunner` and add the task tool when non-null:

```java
    public static ToolRegistry with(List<String> toolNames, SkillCatalog catalog, IO io, TaskRunner taskRunner) {
        List<Tool> tools = new ArrayList<>();
        for (String name : toolNames) {
            Supplier<Tool> factory = BUILT_INS.get(name);
            if (factory == null) {
                throw new ToolException("Unknown tool: " + name);
            }
            tools.add(factory.get());
        }
        tools.add(new EditTool());
        tools.add(new TodowriteTool());
        tools.add(new QuestionTool(io));
        if (taskRunner != null) {
            tools.add(new TaskTool(taskRunner));
        }
        if (catalog != null && !catalog.isEmpty()) {
            tools.add(new SkillTool(catalog));
        }
        return new ToolRegistry(tools);
    }
```

Replace the contents of `src/main/java/com/mrsmith/tool/ToolRegistryFactory.java` with:

```java
package com.mrsmith.tool;

import com.mrsmith.config.AppConfig;
import com.mrsmith.io.IO;
import com.mrsmith.skill.SkillCatalog;

public interface ToolRegistryFactory {

    ToolRegistry create(AppConfig config, SkillCatalog catalog, IO io, TaskRunner taskRunner);
}
```

- [ ] **Step 5: Update ChatCommand**

In `src/main/java/com/mrsmith/cli/ChatCommand.java`, change the factory lambda:

```java
                (config, skillCatalog, terminalIo, taskRunner) -> ToolRegistry.with(config.tools(), skillCatalog, terminalIo, taskRunner),
```

- [ ] **Step 6: Update ChatSession**

In `src/main/java/com/mrsmith/chat/ChatSession.java`:

1. Add a field:
```java
    private SubAgentRunner subAgentRunner;
```

2. In `applyAgent()`, build the runner (its tools exclude `task`) and pass it to the factory:

```java
    private void applyAgent() {
        config = agents.resolve(currentAgentName);
        provider = providerFactory.create(config);
        subAgentRunner = new SubAgentRunner(agents, providerFactory,
                cfg -> ToolRegistry.with(cfg.tools(), skills, io, null),
                io, tracker, () -> config, () -> currentSessionId);
        toolRegistry = toolRegistryFactory.create(config, skills, io, subAgentRunner);
    }
```

3. In `startFreshSession()`, reset the sub-agent counter alongside the registry:

```java
        toolRegistry.resetSession();
        subAgentRunner.reset();
        startNewSession();
```

- [ ] **Step 7: Update the test lambdas (ChatSessionTest)**

In `src/test/java/com/mrsmith/chat/ChatSessionTest.java`:

1. Replace `noToolsFactory()`:
```java
    private ToolRegistryFactory noToolsFactory() {
        return (config, catalog, io, taskRunner) -> new ToolRegistry(List.of());
    }
```

2. Global replace `(config, catalog, io) -> new ToolRegistry(` → `(config, catalog, io, taskRunner) -> new ToolRegistry(`
3. Global replace `(config, catalog, io) -> ToolRegistry.with(` → `(config, catalog, io, taskRunner) -> ToolRegistry.with(`
4. Global replace `ToolRegistry.with(List.of(), catalog, io)` → `ToolRegistry.with(List.of(), catalog, io, taskRunner)` (the inner call inside the skill-test lambdas).

- [ ] **Step 8: Update ToolRegistryTest**

In `src/test/java/com/mrsmith/tool/ToolRegistryTest.java`, update every existing `ToolRegistry.with(...)` call to pass `taskRunner` (the new field) as the 4th argument, and adjust the tool-count assertions:
- `builtInWithNamesCreatesAllRequestedTools`: `assertEquals(10, registry.tools().size());`
- `alwaysOnToolsAddedEvenWhenCatalogEmpty`: `assertEquals(4, ...)` and add `assertTrue(registry.find("task").isPresent());`
- `addsSkillToolWhenCatalogNonEmpty`: `assertEquals(5, ...)`
- `resetSessionClearsSkillToolState` / `resetSessionClearsTodowriteState`: pass `taskRunner`.

- [ ] **Step 9: Run tests to verify they pass**

Run: `mvn -q -Dtest=TaskToolTest,ToolRegistryTest,ChatSessionTest test`
Expected: BUILD SUCCESS.

- [ ] **Step 10: Run the full suite**

Run: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: BUILD SUCCESS (~268 tests).

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/mrsmith/tool/TaskTool.java src/main/java/com/mrsmith/tool/ToolRegistry.java src/main/java/com/mrsmith/tool/ToolRegistryFactory.java src/main/java/com/mrsmith/cli/ChatCommand.java src/main/java/com/mrsmith/chat/ChatSession.java src/test/java/com/mrsmith/tool/TaskToolTest.java src/test/java/com/mrsmith/tool/ToolRegistryTest.java src/test/java/com/mrsmith/chat/ChatSessionTest.java
git commit -m "feat: add task tool and wire the sub-agent runner through the registry"
```

---

### Task 6: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full suite**

Run: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: BUILD SUCCESS — a final `Tests run: N, Failures: 0, Errors: 0, Skipped: 0` line.

- [ ] **Step 2: Confirm the working tree is clean**

Run: `git status --short`
Expected: nothing (all changes committed).
