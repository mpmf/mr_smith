# Reasoning Effort Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow each agent to set an optional `reasoningEffort` string that is passed through to the provider as the `reasoning_effort` request-body field.

**Architecture:** `reasoningEffort` is a new nullable `String` component on the `AgentConfig` record, parsed from `agents[].reasoningEffort` in the config file. `OpenAiCompatibleProvider` emits `reasoning_effort` only when the value is non-blank. No new types, no env/CLI, no validation.

**Tech Stack:** Java 21, JUnit 5 (Jupiter), Maven.

**Spec:** `docs/superpowers/specs/2026-08-18-reasoning-effort-design.md`

---

## File Structure

**Modify (main):**
- `src/main/java/com/mrsmith/config/AgentConfig.java` — add `reasoningEffort` component + update convenience constructors
- `src/main/java/com/mrsmith/config/ConfigLoader.java` — parse `agents[].reasoningEffort`
- `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java` — emit `reasoning_effort` when non-blank

**Modify (test):**
- `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`
- `src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java`
- `src/test/java/com/mrsmith/chat/ContextBuildersTest.java` (canonical 11-arg call site gains `null`)
- `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java` (canonical 11-arg call site gains `null`)

**Modify (docs):**
- `README.md` — document `agents[].reasoningEffort`

---

### Task 1: Add `reasoningEffort` to `AgentConfig`

Adding a record component changes the canonical constructor arity (11 → 12), which breaks the three canonical-constructor call sites (`ConfigLoader`, `ContextBuildersTest`, `SubAgentRunnerTest`). This task updates the record and all call sites so the build is green with `reasoningEffort` still unused (always `null`).

**Files:**
- Modify: `src/main/java/com/mrsmith/config/AgentConfig.java`
- Modify: `src/main/java/com/mrsmith/config/ConfigLoader.java`
- Modify: `src/test/java/com/mrsmith/chat/ContextBuildersTest.java`
- Modify: `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java`

- [ ] **Step 1: Update `AgentConfig`**

Replace `src/main/java/com/mrsmith/config/AgentConfig.java` with (appending `String reasoningEffort` as the 12th field; every convenience constructor passes `null`):

```java
package com.mrsmith.config;

import java.util.List;

public record AgentConfig(String name, String provider, String model,
                          String systemPrompt, Integer maxContextTokens,
                          Integer maxToolRounds, Integer maxToolCallsPerSession,
                          List<String> tools,
                          List<String> shellHarmlessCommands,
                          List<String> shellDangerousCommands,
                          ContextStrategy contextBuilder,
                          String reasoningEffort) {

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens) {
        this(name, provider, model, systemPrompt, maxContextTokens, null, null,
                List.of(), List.of(), List.of(), ContextStrategy.FULL, null);
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, List<String> tools) {
        this(name, provider, model, systemPrompt, maxContextTokens, null, null,
                tools, List.of(), List.of(), ContextStrategy.FULL, null);
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, Integer maxToolRounds) {
        this(name, provider, model, systemPrompt, maxContextTokens, maxToolRounds, null,
                List.of(), List.of(), List.of(), ContextStrategy.FULL, null);
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, Integer maxToolRounds,
                       Integer maxToolCallsPerSession) {
        this(name, provider, model, systemPrompt, maxContextTokens, maxToolRounds,
                maxToolCallsPerSession, List.of(), List.of(), List.of(), ContextStrategy.FULL, null);
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, Integer maxToolRounds,
                       Integer maxToolCallsPerSession, List<String> tools) {
        this(name, provider, model, systemPrompt, maxContextTokens, maxToolRounds,
                maxToolCallsPerSession, tools, List.of(), List.of(), ContextStrategy.FULL, null);
    }
}
```

- [ ] **Step 2: Update `ConfigLoader.parseAgents` canonical call**

In `src/main/java/com/mrsmith/config/ConfigLoader.java`, inside `parseAgents`, add `null` as the 12th argument to the `new AgentConfig(...)` call (right after the `contextBuilder` argument):

```java
                result.add(new AgentConfig(
                        node.path("name").asText(),
                        node.path("provider").asText(),
                        node.path("model").asText(null),
                        node.path("systemPrompt").asText(null),
                        node.hasNonNull("maxContextTokens") ? node.get("maxContextTokens").asInt() : null,
                        node.hasNonNull("maxToolRounds") ? node.get("maxToolRounds").asInt() : null,
                        node.hasNonNull("maxToolCallsPerSession") ? node.get("maxToolCallsPerSession").asInt() : null,
                        parseTools(node),
                        parseStringList(node, "shellHarmlessCommands"),
                        parseStringList(node, "shellDangerousCommands"),
                        node.hasNonNull("contextBuilder")
                                ? ContextStrategy.parse(node.get("contextBuilder").asText())
                                : defaultStrategy,
                        null));
```

- [ ] **Step 3: Update `ContextBuildersTest` canonical call**

In `src/test/java/com/mrsmith/chat/ContextBuildersTest.java`, the `runtime(...)` helper currently builds an 11-arg `AgentConfig`. Append `null` as the 12th argument:

```java
    private static AgentRuntime runtime(String strategy, Integer maxContext, double ratio) {
        AgentConfig agent = new AgentConfig("a", "p", "m", null, maxContext, null, null,
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                ContextStrategy.parse(strategy), null);
        ProviderConfig provider = new ProviderConfig("p", "sk", "https://example.com/v1");
        return new AgentRuntime(agent, provider, new AgentRuntime.Globals(true, ratio));
    }
```

- [ ] **Step 4: Update `SubAgentRunnerTest` canonical call**

In `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java`, in `slidingSubAgentTrimsOldTurnsOnResume` (around line 415), append `null` as the 12th argument to the `new AgentConfig(...)` call:

```java
                List.of(new AgentConfig("a", "p", "m", "sys", 6, null, null,
                        List.of(), List.of(), List.of(), ContextStrategy.SLIDING, null)),
```

- [ ] **Step 5: Run the full suite to verify it compiles and passes**

Run: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: BUILD SUCCESS (field added but unused; all existing behavior unchanged).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/config/AgentConfig.java src/main/java/com/mrsmith/config/ConfigLoader.java src/test/java/com/mrsmith/chat/ContextBuildersTest.java src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java
git commit -m "feat: add reasoningEffort field to AgentConfig"
```

---

### Task 2: Parse `reasoningEffort` from config

**Files:**
- Modify: `src/main/java/com/mrsmith/config/ConfigLoader.java`
- Test: `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`:

```java
    @Test
    void parsesReasoningEffortPerAgent() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m", "reasoningEffort": "high" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals("high", catalog.resolve("a").agent().reasoningEffort());
    }

    @Test
    void reasoningEffortDefaultsToNullWhenAbsent() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(null, catalog.resolve("a").agent().reasoningEffort());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=ConfigLoaderTest test`
Expected: FAIL — `parsesReasoningEffortPerAgent` expects `"high"` but gets `null`.

- [ ] **Step 3: Parse the field**

In `src/main/java/com/mrsmith/config/ConfigLoader.java`, in `parseAgents`, replace the trailing `null` argument (added in Task 1 Step 2) with the config read:

```java
                        null));
```
becomes:
```java
                        node.path("reasoningEffort").asText(null)));
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=ConfigLoaderTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/config/ConfigLoader.java src/test/java/com/mrsmith/config/ConfigLoaderTest.java
git commit -m "feat: parse reasoningEffort from config"
```

---

### Task 3: Emit `reasoning_effort` in the request body

**Files:**
- Modify: `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java`
- Test: `src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java`

- [ ] **Step 1: Write the failing tests**

In `src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java`, add the import for `ContextStrategy` (near the existing `com.mrsmith.config` imports):

```java
import com.mrsmith.config.ContextStrategy;
```

Then append these tests (inside the class body, before the `StubTool` static class):

```java
    @Test
    void sendsReasoningEffortWhenSet() throws Exception {
        server.shutdown();
        server = new MockWebServer();
        server.start();
        AgentRuntime runtime = new AgentRuntime(
                new AgentConfig("a", "p", "test-model", null, null, null, null,
                        List.of(), List.of(), List.of(), ContextStrategy.FULL, "high"),
                new ProviderConfig("p", "sk-test", server.url("/").toString()),
                new AgentRuntime.Globals(true));
        provider = new OpenAiCompatibleProvider(runtime, HttpClient.newHttpClient(), 0L);
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        provider.send(List.of(new ChatMessage(Role.USER, "hello")), List.of(), s -> { }, s -> { });
        RecordedRequest request = server.takeRequest();
        assertTrue(request.getBody().readUtf8().contains("\"reasoning_effort\":\"high\""));
    }

    @Test
    void omitsReasoningEffortWhenUnset() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        provider.send(List.of(new ChatMessage(Role.USER, "hello")), List.of(), s -> { }, s -> { });
        RecordedRequest request = server.takeRequest();
        assertFalse(request.getBody().readUtf8().contains("reasoning_effort"));
    }

    @Test
    void omitsReasoningEffortWhenBlank() throws Exception {
        server.shutdown();
        server = new MockWebServer();
        server.start();
        AgentRuntime runtime = new AgentRuntime(
                new AgentConfig("a", "p", "test-model", null, null, null, null,
                        List.of(), List.of(), List.of(), ContextStrategy.FULL, "  "),
                new ProviderConfig("p", "sk-test", server.url("/").toString()),
                new AgentRuntime.Globals(true));
        provider = new OpenAiCompatibleProvider(runtime, HttpClient.newHttpClient(), 0L);
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        provider.send(List.of(new ChatMessage(Role.USER, "hello")), List.of(), s -> { }, s -> { });
        RecordedRequest request = server.takeRequest();
        assertFalse(request.getBody().readUtf8().contains("reasoning_effort"));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=OpenAiCompatibleProviderTest test`
Expected: FAIL — `sendsReasoningEffortWhenSet` does not find `reasoning_effort` in the body.

- [ ] **Step 3: Emit the field**

In `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java`, in `buildRequestBody`, add after the `root.put("stream", true);` line:

```java
        String effort = runtime.agent().reasoningEffort();
        if (effort != null && !effort.isBlank()) {
            root.put("reasoning_effort", effort);
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=OpenAiCompatibleProviderTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java
git commit -m "feat: emit reasoning_effort request field when configured"
```

---

### Task 4: Update docs

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add the field to the config example**

In `README.md`, in the example config JSON, add `"reasoningEffort"` to the `coder` agent block (after `"maxToolCallsPerSession"`):

```json
      "maxContextTokens": 128000,
      "maxToolRounds": 32,
      "maxToolCallsPerSession": 500,
      "reasoningEffort": "high",
      "contextBuilder": "sliding",
```

- [ ] **Step 2: Add the field to the fields table**

In `README.md`, in the `### Fields` table, add a row after the `agents[].maxToolCallsPerSession` row:

```
| `agents[].reasoningEffort` | Optional `reasoning_effort` value (e.g. `low`, `medium`, `high`) sent verbatim to the provider on every request; omitted when unset or blank |
```

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: document reasoningEffort agent field"
```

---

### Task 5: Final verification

- [ ] **Step 1: Run the full suite**

Run: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Build the shaded jar**

Run: `mvn -q package`
Expected: builds `target/mr-smith.jar` with no errors.
