# Runtime Reasoning Effort Override Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `/reasoning` REPL command that sets, shows, and clears a session-scoped override of the per-agent `reasoningEffort` config value.

**Architecture:** A new mutable `ReasoningEffort` holder is added as a 4th component of `AgentRuntime`. `AgentRuntime.effectiveReasoningEffort()` returns the override when set, else the configured value, and `OpenAiCompatibleProvider` reads that method instead of `agent.reasoningEffort()` directly. `ChatSession` mutates the holder via `/reasoning` and clears it in `startFreshSession()`. Sub-agent inheritance falls out of the object graph: same-agent sub-agents reuse the shared runtime (inheriting the override), named sub-agents resolve a fresh runtime (their own config wins).

**Tech Stack:** Java 21, JUnit 5 (Jupiter), Maven.

**Spec:** `docs/superpowers/specs/2026-08-18-runtime-reasoning-effort-design.md`

---

## File Structure

**Create (main):**
- `src/main/java/com/mrsmith/config/ReasoningEffort.java` — mutable override holder

**Create (test):**
- `src/test/java/com/mrsmith/config/ReasoningEffortTest.java`

**Modify (main):**
- `src/main/java/com/mrsmith/config/AgentRuntime.java` — 4th component + convenience constructor + `effectiveReasoningEffort()`
- `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java` — read `effectiveReasoningEffort()`
- `src/main/java/com/mrsmith/chat/ChatSession.java` — `/reasoning` command; clear on `startFreshSession`; `/help` text

**Modify (test):**
- `src/test/java/com/mrsmith/config/AgentRuntimeTest.java`
- `src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java`
- `src/test/java/com/mrsmith/chat/ChatSessionTest.java`

**Modify (docs):**
- `README.md` — document the `/reasoning` command

---

### Task 1: `ReasoningEffort` holder

**Files:**
- Create: `src/main/java/com/mrsmith/config/ReasoningEffort.java`
- Test: `src/test/java/com/mrsmith/config/ReasoningEffortTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mrsmith/config/ReasoningEffortTest.java`:

```java
package com.mrsmith.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningEffortTest {

    @Test
    void defaultsToUnset() {
        ReasoningEffort effort = new ReasoningEffort();
        assertFalse(effort.isSet());
        assertNull(effort.override());
    }

    @Test
    void setThenOverride() {
        ReasoningEffort effort = new ReasoningEffort();
        effort.set("high");
        assertTrue(effort.isSet());
        assertEquals("high", effort.override());
    }

    @Test
    void clearResetsToUnset() {
        ReasoningEffort effort = new ReasoningEffort();
        effort.set("high");
        effort.clear();
        assertFalse(effort.isSet());
        assertNull(effort.override());
    }

    @Test
    void effectiveReturnsOverrideWhenSet() {
        ReasoningEffort effort = new ReasoningEffort();
        effort.set("high");
        assertEquals("high", effort.effective("low"));
    }

    @Test
    void effectiveReturnsConfiguredWhenNotSet() {
        ReasoningEffort effort = new ReasoningEffort();
        assertEquals("low", effort.effective("low"));
    }

    @Test
    void effectiveReturnsConfiguredWhenOverrideBlank() {
        ReasoningEffort effort = new ReasoningEffort();
        effort.set("   ");
        assertFalse(effort.isSet());
        assertEquals("low", effort.effective("low"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=ReasoningEffortTest test`
Expected: FAIL — cannot resolve `ReasoningEffort`.

- [ ] **Step 3: Create `ReasoningEffort`**

`src/main/java/com/mrsmith/config/ReasoningEffort.java`:

```java
package com.mrsmith.config;

public final class ReasoningEffort {

    private String override;

    public void set(String value) {
        this.override = value;
    }

    public void clear() {
        this.override = null;
    }

    public String override() {
        return override;
    }

    public boolean isSet() {
        return override != null && !override.isBlank();
    }

    public String effective(String configured) {
        return isSet() ? override : configured;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=ReasoningEffortTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/config/ReasoningEffort.java src/test/java/com/mrsmith/config/ReasoningEffortTest.java
git commit -m "feat: add ReasoningEffort mutable override holder"
```

---

### Task 2: `AgentRuntime` 4th component + `effectiveReasoningEffort()`

**Files:**
- Modify: `src/main/java/com/mrsmith/config/AgentRuntime.java`
- Test: `src/test/java/com/mrsmith/config/AgentRuntimeTest.java`

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/com/mrsmith/config/AgentRuntimeTest.java`:

```java
    @Test
    void effectiveReasoningEffortPrefersOverride() {
        AgentConfig agent = new AgentConfig("coder", "p", "model-x", "sys", 8192, 5, 200,
                List.of(), List.of(), List.of(), ContextStrategy.FULL, "low");
        ProviderConfig provider = new ProviderConfig("p", "sk", "https://example.com/v1");
        AgentRuntime runtime = new AgentRuntime(agent, provider, new AgentRuntime.Globals(false));
        assertEquals("low", runtime.effectiveReasoningEffort());
        runtime.reasoning().set("high");
        assertEquals("high", runtime.effectiveReasoningEffort());
    }

    @Test
    void convenienceConstructorDefaultsToEmptyReasoning() {
        AgentConfig agent = new AgentConfig("coder", "p", "model-x", "sys", 8192);
        ProviderConfig provider = new ProviderConfig("p", "sk", "https://example.com/v1");
        AgentRuntime runtime = new AgentRuntime(agent, provider, new AgentRuntime.Globals(false));
        assertFalse(runtime.reasoning().isSet());
    }
```

(Note: `ContextStrategy`, `AgentConfig`, `ProviderConfig` are all in the `com.mrsmith.config` package, so no new imports are needed; `assertEquals`, `assertFalse`, and `List` are already imported.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=AgentRuntimeTest test`
Expected: FAIL — `effectiveReasoningEffort()` and `reasoning()` do not exist.

- [ ] **Step 3: Update `AgentRuntime`**

Replace `src/main/java/com/mrsmith/config/AgentRuntime.java` with:

```java
package com.mrsmith.config;

public record AgentRuntime(AgentConfig agent, ProviderConfig provider, AgentRuntime.Globals globals,
                           ReasoningEffort reasoning) {

    public static final double DEFAULT_CONTEXT_WINDOW_RATIO = 0.75;

    public AgentRuntime(AgentConfig agent, ProviderConfig provider, AgentRuntime.Globals globals) {
        this(agent, provider, globals, new ReasoningEffort());
    }

    public String effectiveReasoningEffort() {
        return reasoning.effective(agent.reasoningEffort());
    }

    public record Globals(boolean includeUsage, double contextWindowRatio) {

        public Globals(boolean includeUsage) {
            this(includeUsage, DEFAULT_CONTEXT_WINDOW_RATIO);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=AgentRuntimeTest test`
Expected: PASS. The 3-arg convenience constructor keeps existing call sites (`AgentCatalog.resolve`, provider tests, `ContextBuildersTest`, `SubAgentRunnerTest`) compiling.

- [ ] **Step 5: Run the full suite to confirm no regressions**

Run: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/config/AgentRuntime.java src/test/java/com/mrsmith/config/AgentRuntimeTest.java
git commit -m "feat: add reasoning effort holder and effective value to AgentRuntime"
```

---

### Task 3: Provider reads the effective value

**Files:**
- Modify: `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java`
- Test: `src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java`

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java` (before the `StubTool` static class at the bottom):

```java
    @Test
    void reasoningEffortOverrideWinsOverConfigured() throws Exception {
        server.shutdown();
        server = new MockWebServer();
        server.start();
        AgentRuntime runtime = new AgentRuntime(
                new AgentConfig("a", "p", "test-model", null, null, null, null,
                        List.of(), List.of(), List.of(), ContextStrategy.FULL, "low"),
                new ProviderConfig("p", "sk-test", server.url("/").toString()),
                new AgentRuntime.Globals(true));
        provider = new OpenAiCompatibleProvider(runtime, HttpClient.newHttpClient(), 0L);
        runtime.reasoning().set("high");
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        provider.send(List.of(new ChatMessage(Role.USER, "hello")), List.of(), s -> { }, s -> { });
        RecordedRequest request = server.takeRequest();
        assertTrue(request.getBody().readUtf8().contains("\"reasoning_effort\":\"high\""));
    }

    @Test
    void reasoningEffortFallsBackToConfigAfterOverrideClear() throws Exception {
        server.shutdown();
        server = new MockWebServer();
        server.start();
        AgentRuntime runtime = new AgentRuntime(
                new AgentConfig("a", "p", "test-model", null, null, null, null,
                        List.of(), List.of(), List.of(), ContextStrategy.FULL, "low"),
                new ProviderConfig("p", "sk-test", server.url("/").toString()),
                new AgentRuntime.Globals(true));
        provider = new OpenAiCompatibleProvider(runtime, HttpClient.newHttpClient(), 0L);
        runtime.reasoning().set("high");
        runtime.reasoning().clear();
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        provider.send(List.of(new ChatMessage(Role.USER, "hello")), List.of(), s -> { }, s -> { });
        RecordedRequest request = server.takeRequest();
        assertTrue(request.getBody().readUtf8().contains("\"reasoning_effort\":\"low\""));
    }
```

(`ContextStrategy` is already imported in this file from the prior reasoning-effort work; `List`, `MockWebServer`, `MockResponse`, `RecordedRequest`, `HttpClient`, `assertTrue`, and `AgentRuntime`/`AgentConfig`/`ProviderConfig` are all already imported.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=OpenAiCompatibleProviderTest test`
Expected: FAIL — `reasoningEffortOverrideWinsOverConfigured` emits `"low"` (the configured value) instead of `"high"`.

- [ ] **Step 3: Update the provider**

In `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java`, in `buildRequestBody`, change:

```java
        String effort = runtime.agent().reasoningEffort();
```
to:
```java
        String effort = runtime.effectiveReasoningEffort();
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=OpenAiCompatibleProviderTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java
git commit -m "feat: provider reads effective reasoning effort from runtime"
```

---

### Task 4: `/reasoning` command in `ChatSession`

**Files:**
- Modify: `src/main/java/com/mrsmith/chat/ChatSession.java`
- Test: `src/test/java/com/mrsmith/chat/ChatSessionTest.java`

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/com/mrsmith/chat/ChatSessionTest.java` (before the `catalog()` helper methods near the bottom):

```java
    @Test
    void reasoningCommandShowsNotSet() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/reasoning", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("Reasoning effort: not set")));
    }

    @Test
    void reasoningCommandSetsOverride() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/reasoning high", "/reasoning", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("Reasoning effort set to: high")));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("Reasoning effort: high")));
    }

    @Test
    void reasoningCommandClearsOverride() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/reasoning high", "/reasoning off", "/reasoning", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("Reasoning effort override cleared")));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("Reasoning effort: not set")));
    }

    @Test
    void resetClearsReasoningOverride() throws Exception {
        List<AgentRuntime> runtimes = new ArrayList<>();
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/reasoning high", "/reset", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, ContextBuilderFactory.full(),
                catalog(), r -> { runtimes.add(r); return provider; }, noToolsFactory(), emptySkills(), "a");
        session.run();
        assertEquals(1, runtimes.size());
        assertFalse(runtimes.get(0).reasoning().isSet());
    }

    @Test
    void agentSwitchDropsReasoningOverride() throws Exception {
        List<AgentRuntime> runtimes = new ArrayList<>();
        AgentCatalog catalog = new AgentCatalog(
                List.of(new ProviderConfig("p", "sk-test", "https://example.com/v1")),
                List.of(new AgentConfig("a", "p", "m", null, null),
                        new AgentConfig("b", "p", "m", null, null)),
                "a", true, Path.of("sessions"));
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/reasoning high", "/agent b", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, ContextBuilderFactory.full(),
                catalog, r -> { runtimes.add(r); return provider; }, noToolsFactory(), emptySkills(), "a");
        session.run();
        assertEquals(2, runtimes.size());
        assertTrue(runtimes.get(0).reasoning().isSet());
        assertFalse(runtimes.get(1).reasoning().isSet());
    }

    @Test
    void helpListsReasoningCommand() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/help", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("/reasoning")));
    }
```

(`AgentRuntime`, `ProviderConfig`, `AgentCatalog`, `AgentConfig`, `ContextBuilderFactory`, `assertFalse`, `ArrayList`, `List`, `Path`, `assertTrue`, and `assertEquals` are already imported in this file.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=ChatSessionTest test`
Expected: FAIL — `/reasoning` is currently treated as an "Unknown command".

- [ ] **Step 3: Add the command handler**

In `src/main/java/com/mrsmith/chat/ChatSession.java`, add to `handleCommand` before the `switch (line)` block (i.e. after the `/skills ` block):

```java
        if (line.equals("/reasoning")) {
            showReasoningEffort();
            return true;
        }
        if (line.startsWith("/reasoning ")) {
            String arg = line.substring("/reasoning ".length()).trim();
            if (arg.isBlank()) {
                showReasoningEffort();
            } else if (arg.equals("off")) {
                clearReasoningEffort();
            } else {
                setReasoningEffort(arg);
            }
            return true;
        }
```

- [ ] **Step 4: Add the helper methods**

In `src/main/java/com/mrsmith/chat/ChatSession.java`, add these methods (e.g. near `listTasks`):

```java
    private void showReasoningEffort() {
        String override = runtime.reasoning().override();
        String configured = runtime.agent().reasoningEffort();
        boolean hasConfig = configured != null && !configured.isBlank();
        if (runtime.reasoning().isSet()) {
            io.writeLine("Reasoning effort: " + override
                    + (hasConfig ? " (override; config: " + configured + ")" : " (override)"));
        } else if (hasConfig) {
            io.writeLine("Reasoning effort: " + configured + " (from config)");
        } else {
            io.writeLine("Reasoning effort: not set");
        }
    }

    private void setReasoningEffort(String value) {
        runtime.reasoning().set(value);
        io.writeLine("Reasoning effort set to: " + value);
    }

    private void clearReasoningEffort() {
        runtime.reasoning().clear();
        String configured = runtime.agent().reasoningEffort();
        io.writeLine("Reasoning effort override cleared"
                + ((configured != null && !configured.isBlank()) ? " (config: " + configured + ")" : ""));
    }
```

- [ ] **Step 5: Clear the override on a fresh session**

In `src/main/java/com/mrsmith/chat/ChatSession.java`, in `startFreshSession()`, add `runtime.reasoning().clear();` as the first line of the method body (before `history.clear();`).

- [ ] **Step 6: Update the `/help` text**

In `src/main/java/com/mrsmith/chat/ChatSession.java`, change the `/help` case line:

```java
            case "/help" -> io.writeLine("Commands: /exit, /reset, /help, /usage, /agents, /agent <name>, /skills [name], /tasks, /reasoning [value|off]. Anything else is sent to the LLM.");
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn -q -Dtest=ChatSessionTest test`
Expected: PASS.

- [ ] **Step 8: Run the full suite**

Run: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: BUILD SUCCESS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ChatSession.java src/test/java/com/mrsmith/chat/ChatSessionTest.java
git commit -m "feat: add /reasoning command to override reasoning effort"
```

---

### Task 5: Update docs

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Document the command**

In `README.md`, in the `### REPL commands` table, add rows after the `/tasks` row:

```
| `/reasoning` | Show the current `reasoning_effort` (override, config, or "not set") |
| `/reasoning <value>` | Override `reasoning_effort` for this session |
| `/reasoning off` | Clear the override (fall back to the configured value) |
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: document /reasoning command"
```

---

### Task 6: Final verification

- [ ] **Step 1: Run the full suite**

Run: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Build the shaded jar**

Run: `mvn -q package`
Expected: builds `target/mr-smith.jar` with no errors.
