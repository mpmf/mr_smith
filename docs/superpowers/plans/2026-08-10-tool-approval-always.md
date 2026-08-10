# "Always Allow" Tool Approval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an `always` option to the non-read-only tool approval prompt so the user can approve a specific tool for the rest of the session without being re-prompted.

**Architecture:** A session-scoped `ToolApproval` object (a `Set<String>` of approved tool names) is created and reset by `ChatSession`, threaded into `ToolLoop.run`, and shared with sub-agents via `SubAgentRunner.Context`. `ToolLoop.confirm` becomes tri-state (`y`/`a`/decline); `executeTool` skips the prompt when the tool name is already always-allowed, and records the name when the user answers `a`.

**Tech Stack:** Java 21, JUnit 5 (Jupiter), Maven.

---

## File Structure

**Create (main):**
- `src/main/java/com/mrsmith/chat/ToolApproval.java` — session-scoped approved-tool set

**Modify (main):**
- `src/main/java/com/mrsmith/chat/ToolLoop.java` — `run`/`executeTool` gain `ToolApproval`; tri-state `confirm` + `ConfirmDecision`
- `src/main/java/com/mrsmith/chat/ChatSession.java` — field, reset in `startFreshSession()`, pass into `ToolLoop.run` and `SubAgentRunner.Context`
- `src/main/java/com/mrsmith/chat/SubAgentRunner.java` — `Context` gains `Supplier<ToolApproval>`, pass into `ToolLoop.run`

**Modify (test):**
- `src/test/java/com/mrsmith/chat/ChatSessionTest.java` — `alwaysAllowsToolWithoutReprompting`, `alwaysAllowClearedOnReset`
- `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java` — `subAgentSharesAlwaysAllowDecision`; Context call sites gain the new supplier arg

**Modify (docs):**
- `docs/superpowers/specs/2026-08-03-tool-calling-design.md` — approval section: `[y/N]` → `[y/N/a]`
- `README.md` — tool-calling feature bullet mention of the always-allow option

---

### Task 1: Add the session-scoped `ToolApproval`

**Files:**
- Create: `src/main/java/com/mrsmith/chat/ToolApproval.java`

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/mrsmith/chat/ChatSessionTest.java` a direct unit test of the holder (insert after `declinesNonReadOnlyTool`):

```java
    @Test
    void toolApprovalTracksAndResets() {
        ToolApproval approval = new ToolApproval();
        assertFalse(approval.isAlwaysAllowed("shell"));
        approval.allowAlways("shell");
        assertTrue(approval.isAlwaysAllowed("shell"));
        assertFalse(approval.isAlwaysAllowed("write_file"));
        approval.reset();
        assertFalse(approval.isAlwaysAllowed("shell"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=ChatSessionTest#toolApprovalTracksAndResets test`
Expected: FAIL — cannot resolve `ToolApproval`.

- [ ] **Step 3: Create `ToolApproval`**

`src/main/java/com/mrsmith/chat/ToolApproval.java`:

```java
package com.mrsmith.chat;

import java.util.HashSet;
import java.util.Set;

/** Session-scoped record of tool names the user approved to run without prompting. */
public final class ToolApproval {

    private final Set<String> alwaysAllowed = new HashSet<>();

    public boolean isAlwaysAllowed(String toolName) {
        return alwaysAllowed.contains(toolName);
    }

    public void allowAlways(String toolName) {
        alwaysAllowed.add(toolName);
    }

    public void reset() {
        alwaysAllowed.clear();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=ChatSessionTest#toolApprovalTracksAndResets test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ToolApproval.java src/test/java/com/mrsmith/chat/ChatSessionTest.java
git commit -m "feat: add session-scoped ToolApproval holder"
```

---

### Task 2: Thread `ToolApproval` through `ToolLoop`

**Files:**
- Modify: `src/main/java/com/mrsmith/chat/ToolLoop.java`
- Modify: `src/test/java/com/mrsmith/chat/ChatSessionTest.java`

- [ ] **Step 1: Update the approval prompt assertion helper**

In `src/test/java/com/mrsmith/chat/ChatSessionTest.java` the existing `declinesNonReadOnlyTool` and `confirmsNonReadOnlyToolOnYes` tests use inputs `"n"` and `"y"` — those still work unchanged. No changes needed to them.

- [ ] **Step 2: Add the always-allow session tests**

Append to `src/test/java/com/mrsmith/chat/ChatSessionTest.java`:

```java
    @Test
    void alwaysAllowsToolWithoutReprompting() throws Exception {
        FakeTool shell = new FakeTool("shell", false, new ToolResult("ran", false));
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> new ToolRegistry(List.of(shell));
        FakeToolProvider toolProvider = new FakeToolProvider();
        toolProvider.alwaysCall("shell", JSON.readTree("{\"command\":\"ls\"}"));
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "a", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        assertEquals(9, shell.calls);
        long prompts = io.lines.stream().filter(l -> l.startsWith("Run shell(")).count();
        assertEquals(1, prompts);
        assertFalse(io.lines.stream().anyMatch(l -> l.contains("declined")));
    }

    @Test
    void alwaysAllowClearedOnReset() throws Exception {
        FakeTool shell = new FakeTool("shell", false, new ToolResult("ran", false));
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> new ToolRegistry(List.of(shell));
        AlternatingToolProvider provider = new AlternatingToolProvider(
                new ToolCall("c1", "shell", JSON.readTree("{\"command\":\"ls\"}")));
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "a", "/reset", "again", "a", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(provider), registryFactory, emptySkills(), "a");
        session.run();
        assertEquals(2, shell.calls);
        long prompts = io.lines.stream().filter(l -> l.startsWith("Run shell(")).count();
        assertEquals(2, prompts);
    }
```

Add the helper provider next to `FirstThenProvider` in `ChatSessionTest`:

```java
    static class AlternatingToolProvider implements Provider {
        final ToolCall call;
        final List<List<ChatMessage>> receivedHistories = new ArrayList<>();
        int calls = 0;

        AlternatingToolProvider(ToolCall call) {
            this.call = call;
        }

        @Override
        public ProviderResponse send(List<ChatMessage> history, List<Tool> tools,
                                     Consumer<String> tokenSink, Consumer<String> reasoningSink) {
            receivedHistories.add(new ArrayList<>(history));
            calls++;
            if (calls == 1 || calls == 3) {
                return new ProviderResponse(
                        new ChatMessage(Role.ASSISTANT, null, null, List.of(call), null),
                        new Usage(0, 0), false);
            }
            return new ProviderResponse(new ChatMessage(Role.ASSISTANT, "answer " + calls),
                    new Usage(0, 0), false);
        }
    }
```

Note on counts: the same provider instance lives across turns (it is only
recreated by `applyAgent()`, which `/reset` does not call). Turn 1's tool call
is provider send 1; turn 2's (after `/reset`) is send 3. So the tool runs once
per turn and the prompt appears once per turn — proving the approval was
cleared.

Note on counts in `alwaysAllowsToolWithoutReprompting`: `alwaysCall` returns a tool call for provider sends 1–9 and the final answer on send 10, so the tool executes 9 times. With one `a` approval the prompt must appear only once.

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn -q -Dtest=ChatSessionTest test`
Expected: FAIL — the new tests fail because the prompt still has no `a` option and the approval is never remembered.

- [ ] **Step 4: Modify `ToolLoop`**

In `src/main/java/com/mrsmith/chat/ToolLoop.java`:

1. Change the `run` signature to take `ToolApproval approval`:

```java
    public static LoopResult run(ContextBuilder context, Provider provider, List<Tool> tools,
                                 IO io, int maxToolRounds, ToolBudget budget, Sink sink,
                                 ToolApproval approval) {
```

2. Change the execute call to pass approval:

```java
                ToolResult result = executeTool(call, tools, io, approval);
```

3. Replace `executeTool` with the approval-aware version and add the `ConfirmDecision` enum:

```java
    private enum ConfirmDecision { ALLOW, ALWAYS_ALLOW, DECLINE }

    private static ToolResult executeTool(ToolCall call, List<Tool> tools, IO io, ToolApproval approval) {
        Optional<Tool> found = find(tools, call.name());
        if (found.isEmpty()) {
            return new ToolResult("Unknown tool: " + call.name(), true);
        }
        Tool tool = found.get();
        if (!tool.isReadOnly()) {
            if (!approval.isAlwaysAllowed(tool.name())) {
                ConfirmDecision decision = confirm(call, tool, io);
                if (decision == ConfirmDecision.DECLINE) {
                    return new ToolResult("User declined to run " + call.name() + ".", true);
                }
                if (decision == ConfirmDecision.ALWAYS_ALLOW) {
                    approval.allowAlways(tool.name());
                }
            }
        }
        try {
            return tool.execute(call.arguments());
        } catch (ToolException e) {
            return new ToolResult(e.getMessage(), true);
        }
    }
```

4. Replace `confirm` with the tri-state version:

```java
    private static ConfirmDecision confirm(ToolCall call, Tool tool, IO io) {
        io.writePrompt("Run " + tool.name() + "(" + describe(call) + ") [y/N/a]? ");
        String answer;
        try {
            answer = io.readLine();
        } catch (IOException e) {
            return ConfirmDecision.DECLINE;
        }
        if (answer == null) {
            return ConfirmDecision.DECLINE;
        }
        String trimmed = answer.trim();
        if (trimmed.equalsIgnoreCase("a") || trimmed.equalsIgnoreCase("always")) {
            return ConfirmDecision.ALWAYS_ALLOW;
        }
        if (trimmed.equalsIgnoreCase("y") || trimmed.equalsIgnoreCase("yes")) {
            return ConfirmDecision.ALLOW;
        }
        return ConfirmDecision.DECLINE;
    }
```

- [ ] **Step 5: Fix the other `ToolLoop.run` call site so the code compiles**

The `SubAgentRunner` and `ChatSession` call sites now fail to compile; they are fixed in Task 3. To keep `mvn test` green between commits, implement Task 3 before committing Task 2.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ToolLoop.java src/test/java/com/mrsmith/chat/ChatSessionTest.java
git commit -m "feat: add always-allow option to tool approval prompt"
```

---

### Task 3: Wire `ToolApproval` through `ChatSession` and `SubAgentRunner`

**Files:**
- Modify: `src/main/java/com/mrsmith/chat/ChatSession.java`
- Modify: `src/main/java/com/mrsmith/chat/SubAgentRunner.java`
- Modify: `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java`

- [ ] **Step 1: Update the sub-agent sharing test**

Append to `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java`:

```java
    @Test
    void subAgentSharesAlwaysAllowDecision() throws Exception {
        FakeTool edit = new FakeTool("edit", false, new ToolResult("edited", false));
        ToolRegistry tools = new ToolRegistry(List.of(edit));
        FakeProvider provider = new FakeProvider(
                new ToolCall("c1", "edit", JSON.readTree("{\"filePath\":\"a.txt\"}")));
        ToolApproval approval = new ToolApproval();
        approval.allowAlways("edit");
        StubIo io = new StubIo(List.of());
        Files.createDirectories(tempDir.resolve(sessionId.toString()));
        AgentCatalog catalog = catalog();
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRunner.Context(catalog,
                new FakeProviderFactory(provider), fixedRegistry(tools), emptySkills(), io,
                new UsageTracker(), () -> catalog.resolve("a"), () -> sessionId,
                () -> new ToolBudget(null, io), () -> approval));
        TaskResult result = runner.run("edit it", null, null);
        assertFalse(result.error());
        assertEquals(1, edit.calls);
        assertTrue(io.lines.stream().noneMatch(l -> l.contains("Run edit(")));
    }
```

- [ ] **Step 2: Update all existing `SubAgentRunner.Context` construction sites**

Every `new SubAgentRunner.Context(...)` in `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java` (in `runner(...)` at line ~68 and in the four inline tests at lines ~318, ~332, ~351, ~379) gains a trailing `() -> new ToolApproval()` argument after the budget supplier. For example, the `runner(...)` helper becomes:

```java
        return new SubAgentRunner(new SubAgentRunner.Context(catalog, factory, fixedRegistry(tools),
                emptySkills(), io, tracker, () -> catalog.resolve("a"), () -> sessionId,
                () -> new ToolBudget(null, io), () -> new ToolApproval()));
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn -q -Dtest=SubAgentRunnerTest test`
Expected: FAIL — cannot resolve the 10-arg `Context` constructor.

- [ ] **Step 4: Modify `SubAgentRunner`**

In `src/main/java/com/mrsmith/chat/SubAgentRunner.java`:

1. Add the supplier to the `Context` record:

```java
    public record Context(AgentCatalog agents, ProviderFactory providerFactory,
                          ToolRegistryFactory toolRegistryFactory, SkillCatalog skills,
                          IO io, UsageTracker tracker,
                          Supplier<AgentRuntime> currentConfig,
                          Supplier<UUID> sessionId, Supplier<ToolBudget> budget,
                          Supplier<ToolApproval> approval) {
    }
```

2. Add the field and constructor assignment:

```java
    private final Supplier<ToolBudget> budget;
    private final Supplier<ToolApproval> approval;
```

```java
        this.budget = context.budget();
        this.approval = context.approval();
```

3. Pass `approval.get()` into the `ToolLoop.run` call:

```java
            ToolLoop.LoopResult result = ToolLoop.run(context, provider, tools.tools(),
                    io, maxToolRounds(config), budget.get(), sinkFor(context, transcripts),
                    approval.get());
```

- [ ] **Step 5: Modify `ChatSession`**

In `src/main/java/com/mrsmith/chat/ChatSession.java`:

1. Add the field (next to `toolBudget`):

```java
    private ToolBudget toolBudget;
    private ToolApproval toolApproval = new ToolApproval();
```

2. Reset it in `startFreshSession()` (next to the `toolBudget` reset):

```java
        toolBudget = new ToolBudget(runtime.agent().maxToolCallsPerSession(), io);
        toolApproval.reset();
```

3. Pass it into `ToolLoop.run` — the anonymous `Sink` is the 7th argument; add `, toolApproval` after its closing paren:

```java
        ToolLoop.LoopResult result = ToolLoop.run(contextBuilder, provider, toolRegistry.tools(),
                io, maxToolRounds(), toolBudget, new ToolLoop.Sink() {
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
                }, toolApproval);
```

4. Pass the supplier into `SubAgentRunner.Context`:

```java
        subAgentRunner = new SubAgentRunner(new SubAgentRunner.Context(
                agents, providerFactory, toolRegistryFactory, skills, io, tracker,
                () -> runtime, () -> currentSessionId, () -> toolBudget, () -> toolApproval));
```

- [ ] **Step 6: Run the full suite to verify it passes**

Run: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: BUILD SUCCESS (all tests, including the new ones).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ChatSession.java src/main/java/com/mrsmith/chat/SubAgentRunner.java src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java
git commit -m "feat: share always-allow tool approvals across the session and sub-agents"
```

---

### Task 4: Update the docs

**Files:**
- Modify: `docs/superpowers/specs/2026-08-03-tool-calling-design.md`
- Modify: `README.md`

- [ ] **Step 1: Tool-calling design doc**

In `docs/superpowers/specs/2026-08-03-tool-calling-design.md`, update the **Approval** paragraph (currently `[y/N]`, "anything but `y`/`yes` ... is a decline"):

1. Change the prompt text to `Run <name>(<args>) [y/N/a]?`.
2. Add: `a`/`always` (case-insensitive) approves and remembers the tool name for the rest of the session; approved tools skip the prompt until `/reset` or an agent switch clears them.

- [ ] **Step 2: README**

In `README.md`, extend the tool-calling feature bullet (the sentence about confirmation prompts): add that confirmation prompts offer an `always` option to approve a tool for the rest of the session.

- [ ] **Step 3: Commit**

```bash
git add README.md docs/superpowers/specs/2026-08-03-tool-calling-design.md
git commit -m "docs: describe the always-allow tool approval option"
```
