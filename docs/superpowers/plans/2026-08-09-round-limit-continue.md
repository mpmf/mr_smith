# Round-Limit Continue Prompt Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the tool round-limit hard stop with a user decision: when `maxToolRounds` is reached, the loop prompts `[y/N]`; yes resets the counter and continues in the same turn, no/EOF performs the existing hard stop.

**Architecture:** Modify `ToolLoop.run`'s round-limit branch to call a new `userWantsToContinue(io, maxToolRounds)` helper (mirroring the approval `confirm()` pattern). On yes, set `round = -1` (the for-loop restarts at 0) and fall through to process the pending calls; on no/EOF, inject the simple limit message and take the forced final answer. The shared loop means main and sub-agent loops both prompt. `roundLimitMessage` reverts to the simple form; the per-session `ToolBudget` is untouched.

**Tech Stack:** Java 21, JUnit 5 (Jupiter), Maven.

---

## File Structure

**Modify (main):**
- `src/main/java/com/mrsmith/chat/ToolLoop.java` — round-limit branch + `userWantsToContinue`; `roundLimitMessage` reverted

**Modify (test):**
- `src/test/java/com/mrsmith/chat/ChatSessionTest.java` — decline inputs on the two round-limit tests; new `continuesToolRoundsWhenUserExtends`
- `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java` — new `subAgentContinuesToolRoundsWhenUserExtends`

**Modify (docs):**
- `docs/superpowers/specs/2026-08-03-tool-calling-design.md` — round-limit branch/error-handling text
- `docs/superpowers/specs/2026-08-08-subagents-design.md` — round-limit references
- `README.md` — tool-loop feature bullet

---

### Task 1: Prompt at the round limit

**Files:**
- Modify: `src/main/java/com/mrsmith/chat/ToolLoop.java`
- Modify: `src/test/java/com/mrsmith/chat/ChatSessionTest.java`
- Modify: `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java`

- [ ] **Step 1: Update the decline-path tests**

In `src/test/java/com/mrsmith/chat/ChatSessionTest.java`:

1. `stopsAtToolRoundLimit` — the prompt now reads a line at the limit; supply an explicit decline so the hard-stop assertions are exercised and `/exit` isn't consumed by the prompt. Change `new StubIo(List.of("hello", "/exit"))` to `new StubIo(List.of("hello", "n", "/exit"))`. Assertions unchanged (`assertEquals(10, provider.calls)`, last tool message contains `"round limit"`, `"call_x"` toolCallId, transcript contains `"round limit"`).

2. `toolRoundLimitHonorsConfig` — same change: inputs become `List.of("hello", "n", "/exit")`. Assertions unchanged (4 calls, `round limit (2)`).

- [ ] **Step 2: Add the extension-path tests**

Append to `src/test/java/com/mrsmith/chat/ChatSessionTest.java`:

```java
    @Test
    void continuesToolRoundsWhenUserExtends() throws Exception {
        FakeTool tool = new FakeTool("read_file", true, new ToolResult("data", false));
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> new ToolRegistry(List.of(tool));
        FakeToolProvider provider = new FakeToolProvider();
        provider.alwaysCall("read_file", JSON.readTree("{\"path\":\"a.txt\"}"));
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "y", "/exit"));
        AgentCatalog catalog = new AgentCatalog(
                List.of(new ProviderConfig("p", "sk-test", "https://example.com/v1")),
                List.of(new AgentConfig("a", "p", "m", null, null, 2)),
                "a", true, Path.of("sessions"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog, new FakeProviderFactory(provider), registryFactory, emptySkills(), "a");
        session.run();
        assertEquals(7, provider.calls);
        assertEquals(5, tool.calls);
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("Continue with 2 more tool rounds")));
    }
```

Append to `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java`:

```java
    @Test
    void subAgentContinuesToolRoundsWhenUserExtends() throws Exception {
        AgentCatalog catalog = new AgentCatalog(
                List.of(new ProviderConfig("p", "sk-test", "https://example.com/v1")),
                List.of(new AgentConfig("a", "p", "m", null, null, 2)),
                "a", true, tempDir);
        FakeTool readFile = new FakeTool("read_file", true, new ToolResult("contents", false));
        ToolRegistry tools = new ToolRegistry(List.of(readFile));
        FakeProvider provider = new FakeProvider(
                new ToolCall("c1", "read_file", JSON.readTree("{}")),
                new ToolCall("c2", "read_file", JSON.readTree("{}")),
                new ToolCall("c3", "read_file", JSON.readTree("{}")),
                new ToolCall("c4", "read_file", JSON.readTree("{}")),
                new ToolCall("c5", "read_file", JSON.readTree("{}")),
                new ToolCall("c6", "read_file", JSON.readTree("{}")));
        StubIo io = new StubIo(List.of("y", "n"));
        Files.createDirectories(tempDir.resolve(sessionId.toString()));
        SubAgentRunner runner = new SubAgentRunner(catalog, new FakeProviderFactory(provider),
                cfg -> tools, io, new UsageTracker(), () -> catalog.resolve("a"), () -> sessionId,
                () -> new ToolBudget(null, io));
        TaskResult result = runner.run("do it", null, null);
        assertFalse(result.error());
        assertEquals(5, readFile.calls);
    }
```

Note on counts: with limit 2, `y` at the first limit grants rounds 0–1 again; the pending call at the *second* limit is dropped (it receives the limit message, not execution), so 5 calls execute and the provider makes 7 sends (6 call-rounds + the final forced send).

- [ ] **Step 3: Run the tests to verify they fail**

Run: `mvn -q -Dtest=ChatSessionTest,SubAgentRunnerTest test`
Expected: FAIL — the new tests assert the prompt text / extended counts that the current hard-stop loop does not produce.

- [ ] **Step 4: Modify ToolLoop**

In `src/main/java/com/mrsmith/chat/ToolLoop.java`:

1. Change the round-limit branch from:

```java
            if (round >= maxToolRounds) {
                String limitContent = roundLimitMessage(maxToolRounds);
                for (ToolCall call : calls) {
                    sink.toolResult(call.id(), limitContent, false);
                }
                return finalAnswer(acc, context, provider, tools, io);
            }
```

to:

```java
            if (round >= maxToolRounds) {
                if (!userWantsToContinue(io, maxToolRounds)) {
                    String limitContent = roundLimitMessage(maxToolRounds);
                    for (ToolCall call : calls) {
                        sink.toolResult(call.id(), limitContent, false);
                    }
                    return finalAnswer(acc, context, provider, tools, io);
                }
                round = -1;
            }
```

2. Revert `roundLimitMessage` and add `userWantsToContinue`:

```java
    private static String roundLimitMessage(int maxToolRounds) {
        return "Tool round limit (" + maxToolRounds + ") reached; answer without more tool calls.";
    }

    private static boolean userWantsToContinue(IO io, int maxToolRounds) {
        io.writePrompt("Tool round limit (" + maxToolRounds + ") reached. Continue with "
                + maxToolRounds + " more tool rounds? [y/N] ");
        String answer;
        try {
            answer = io.readLine();
        } catch (IOException e) {
            return false;
        }
        return answer != null && (answer.trim().equalsIgnoreCase("y")
                || answer.trim().equalsIgnoreCase("yes"));
    }
```

`IO.writePrompt` already exists (added with the colored-prompt work). `round = -1` makes the for-loop's `round++` restart at 0.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q -Dtest=ChatSessionTest,SubAgentRunnerTest test`
Expected: BUILD SUCCESS.

Run: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: BUILD SUCCESS (291 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ToolLoop.java src/test/java/com/mrsmith/chat/ChatSessionTest.java src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java
git commit -m "feat: ask the user to continue before stopping at the tool round limit"
```

---

### Task 2: Update the docs

**Files:**
- Modify: `docs/superpowers/specs/2026-08-03-tool-calling-design.md`
- Modify: `docs/superpowers/specs/2026-08-08-subagents-design.md`
- Modify: `README.md`

- [ ] **Step 1: Tool-calling design doc**

In `docs/superpowers/specs/2026-08-03-tool-calling-design.md`, update the session-loop pseudocode and the round-limit explanation:

1. Replace the round-limit branch pseudocode with the prompt version:

```
  if rounds >= maxToolRounds:                 // default 32
      if not userWantsToContinue():           // prompt "[y/N]" via IO
          for each call in message.toolCalls():
              context += ChatMessage(TOOL, "Tool round limit (<n>) reached; "
                  + "answer without more tool calls.", toolCallId = call.id())
          response = provider.send(context, registry.tools(), io::write, io::writeReasoning)
          final = response.message()          // reply text used; any tool calls dropped
          break
      rounds = -1                              // reset so the next iteration starts fresh
  history += message                         // assistant with tool_calls
```

2. Replace the "round-limit branch appends a TOOL result..." paragraph with the prompt description (yes resets `round = -1` and continues in-turn; no/EOF injects the limit message and forces the final answer; the user decides each time; no cap on extensions; context-limit warnings guard growth).

3. In the Error handling section, replace the round-limit bullet with the prompt behavior (prompt `[y/N]`; yes resets; no appends the `TOOL` message and forces the answer).

- [ ] **Step 2: Sub-agents design doc**

In `docs/superpowers/specs/2026-08-08-subagents-design.md`:

1. In "Configurable tool round limit": change "The 8-round tool loop cap" → "The tool loop cap (default 32)", "Omitted → default 8" → "Omitted → default 32", and describe the prompt (on decline it injects `Tool round limit (<n>) reached; answer without more tool calls.`).
2. In the error table: "Sub-agent round limit" → `prompts the user to continue, like the main loop, capped at the agent's maxToolRounds`.
3. In Testing: "8-round cap" → `honors the agent's maxToolRounds`.

- [ ] **Step 3: README**

In `README.md`, extend the tool-calling feature bullet with: "When the round cap is reached the loop asks whether to continue with a fresh set of rounds, so it is not a hard stop unless you decline."

- [ ] **Step 4: Commit**

```bash
git add README.md docs/superpowers/specs/2026-08-03-tool-calling-design.md docs/superpowers/specs/2026-08-08-subagents-design.md
git commit -m "docs: describe the round-limit continue prompt"
```
