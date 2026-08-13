# Current Context Size in Status Line Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the context builder's estimated current context size in the per-turn status line and the `/usage` report.

**Architecture:** Add a `default int estimatedTokens()` to `ContextBuilder` that sums `TokenEstimator.estimateMessageTokens` over `messages()`; `SlidingWindowContextBuilder` overrides it with its cached `systemTokens + turnTokens`. `ChatSession` reads it and appends ` · context %,d (est.)` to the per-turn line and a `  context: %,d tokens (est.)` line to `/usage`.

**Tech Stack:** Java 21, JUnit 5 (Jupiter), Maven.

**Spec:** `docs/superpowers/specs/2026-08-13-context-size-status-design.md`

---

## File Structure

**Modify (main):**
- `src/main/java/com/mrsmith/chat/ContextBuilder.java` — add `default int estimatedTokens()`
- `src/main/java/com/mrsmith/chat/SlidingWindowContextBuilder.java` — override `estimatedTokens()`
- `src/main/java/com/mrsmith/chat/ChatSession.java` — append context size to per-turn line and `/usage`

**Modify (test):**
- `src/test/java/com/mrsmith/chat/SlidingWindowContextBuilderTest.java`
- `src/test/java/com/mrsmith/chat/FullContextBuilderTest.java`
- `src/test/java/com/mrsmith/chat/ChatSessionTest.java`

---

### Task 1: `ContextBuilder.estimatedTokens()`

**Files:**
- Modify: `src/main/java/com/mrsmith/chat/ContextBuilder.java`
- Modify: `src/main/java/com/mrsmith/chat/SlidingWindowContextBuilder.java`
- Test: `src/test/java/com/mrsmith/chat/SlidingWindowContextBuilderTest.java`
- Test: `src/test/java/com/mrsmith/chat/FullContextBuilderTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/mrsmith/chat/SlidingWindowContextBuilderTest.java`:

```java
    @Test
    void estimatedTokensIsZeroWhenEmpty() {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start(null, 1000);
        assertEquals(0, builder.estimatedTokens());
    }

    @Test
    void estimatedTokensMatchesCurrentWindow() {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start(null, 1000);
        builder.appendUser("hello");    // 2 tokens
        builder.appendAssistant("hi");  // 1 token
        assertEquals(3, builder.estimatedTokens());
    }

    @Test
    void estimatedTokensDropsAfterTrim() {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start(null, 5);
        builder.appendUser("1111");          // 1 token
        builder.appendAssistant("22222222"); // 2 tokens (turn 1 = 3)
        builder.appendUser("33333333");      // 2 tokens (total 5)
        builder.appendAssistant("4444");     // 1 token -> total 6, drop turn 1
        assertEquals(3, builder.estimatedTokens());
    }
```

Add to `src/test/java/com/mrsmith/chat/FullContextBuilderTest.java`:

```java
    @Test
    void estimatedTokensSumsMessages() {
        FullContextBuilder builder = new FullContextBuilder();
        builder.start(null);
        builder.appendUser("hello");    // 2 tokens
        builder.appendAssistant("hi");  // 1 token
        assertEquals(3, builder.estimatedTokens());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=SlidingWindowContextBuilderTest,FullContextBuilderTest test`
Expected: FAIL — cannot resolve `estimatedTokens`.

- [ ] **Step 3: Add the interface method and the sliding override**

In `src/main/java/com/mrsmith/chat/ContextBuilder.java`, add the import and the default method. The imports currently are:

```java
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.ToolCall;

import java.util.List;
```

Add `import com.mrsmith.provider.TokenEstimator;` after the `ToolCall` import, and add the method after `messages()`:

```java
    List<ChatMessage> messages();

    default int estimatedTokens() {
        int total = 0;
        for (ChatMessage message : messages()) {
            total += TokenEstimator.estimateMessageTokens(message);
        }
        return total;
    }
```

In `src/main/java/com/mrsmith/chat/SlidingWindowContextBuilder.java`, add the override (after `messages()`):

```java
    @Override
    public int estimatedTokens() {
        return systemTokens + turnTokens;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=SlidingWindowContextBuilderTest,FullContextBuilderTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ContextBuilder.java src/main/java/com/mrsmith/chat/SlidingWindowContextBuilder.java src/test/java/com/mrsmith/chat/SlidingWindowContextBuilderTest.java src/test/java/com/mrsmith/chat/FullContextBuilderTest.java
git commit -m "feat: expose estimated context size on ContextBuilder"
```

---

### Task 2: Show context size in the status line and `/usage`

**Files:**
- Modify: `src/main/java/com/mrsmith/chat/ChatSession.java`
- Test: `src/test/java/com/mrsmith/chat/ChatSessionTest.java`

- [ ] **Step 1: Update the failing assertions**

In `src/test/java/com/mrsmith/chat/ChatSessionTest.java`:

1. In `printsPerTurnUsageLine`, change the assertion (line ~156) to include the context suffix. Input "hello" → context `[USER "hello" (2), ASSISTANT "hello response" (4)]` = 6 tokens:

```java
        assertTrue(io.lines.contains("tokens: 1,200 in · 300 out · total 1,500 · session 1,500 · context 6 (est.)"));
```

2. In `usageLineFlagsEstimates`, change the assertion (line ~166) similarly:

```java
        assertTrue(io.lines.contains("tokens: 100 in (est.) · 50 out (est.) · total 150 · session 150 (est.) · context 6 (est.)"));
```

3. In `usageCommandPrintsReport`, add a new assertion after the existing three (the context after the "hello" turn is 6 tokens):

```java
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("  context: 6 tokens (est.)")));
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=ChatSessionTest#printsPerTurnUsageLine+usageLineFlagsEstimates+usageCommandPrintsReport test`
Expected: FAIL — the per-turn lines and `/usage` don't yet include the context size.

- [ ] **Step 3: Wire `ChatSession`**

In `src/main/java/com/mrsmith/chat/ChatSession.java`:

1. In `run()`, replace the per-turn line block (currently):

```java
                tracker.recordTurn(turn.usage(), turn.estimated());
                String usageLine = tracker.lastTurnLine();
                if (!usageLine.isEmpty()) {
                    io.writeLine(usageLine);
                }
```

with:

```java
                tracker.recordTurn(turn.usage(), turn.estimated());
                String usageLine = tracker.lastTurnLine();
                if (!usageLine.isEmpty()) {
                    io.writeLine(usageLine + String.format(Locale.US, " · context %,d (est.)",
                            contextBuilder.estimatedTokens()));
                }
```

2. In `usageReport()`, add the context line between the context-limit block and the history line (currently):

```java
        StringBuilder report = new StringBuilder(tracker.usageReport());
        if (contextLimitConfigured()) {
            report.append(String.format(Locale.US, "%n  context limit: %,d configured (%d%% used)",
                    runtime.agent().maxContextTokens(), pctOfMax()));
        }
        report.append(String.format(Locale.US, "%n  history: %d messages", history.size()));
```

change to:

```java
        StringBuilder report = new StringBuilder(tracker.usageReport());
        if (contextLimitConfigured()) {
            report.append(String.format(Locale.US, "%n  context limit: %,d configured (%d%% used)",
                    runtime.agent().maxContextTokens(), pctOfMax()));
        }
        report.append(String.format(Locale.US, "%n  context: %,d tokens (est.)",
                contextBuilder.estimatedTokens()));
        report.append(String.format(Locale.US, "%n  history: %d messages", history.size()));
```

- [ ] **Step 4: Run the full suite to verify it passes**

Run: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ChatSession.java src/test/java/com/mrsmith/chat/ChatSessionTest.java
git commit -m "feat: show estimated context size in status line and /usage"
```

---

## Self-Review Notes

- **Spec coverage:** `estimatedTokens()` default + sliding override (Task 1); per-turn line and `/usage` wiring (Task 2); `(est.)` suffix and both-builders behavior covered.
- **Type consistency:** `estimatedTokens()` defined once in `ContextBuilder` (default) and overridden in `SlidingWindowContextBuilder`; `ChatSession` calls `contextBuilder.estimatedTokens()`.
- **Token math (for test assertions):** `estimateTokens = ceil(chars/4)`; "hello"=2, "hello response"=4 → context 6 in the two per-turn tests.
