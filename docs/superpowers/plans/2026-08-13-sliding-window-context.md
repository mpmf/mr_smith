# Sliding-Window Context Builder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `SlidingWindowContextBuilder` that bounds the context sent to the provider to a token budget, selectable per agent (with a global default), keeping system messages and tool call/result pairs intact.

**Architecture:** A new `ContextStrategy` enum and config fields (`contextBuilder`, `contextWindowRatio`) flow from config → `AgentConfig`/`AgentRuntime.Globals` → a `ContextBuilderFactory` seam (mirroring `ProviderFactory`). `ChatSession` recreates the builder from the factory whenever the agent changes and passes a per-agent budget to `start(...)`. `SlidingWindowContextBuilder` trims complete turns (user message through following assistant/tool messages) from the front on append.

**Tech Stack:** Java 21, JUnit 5 (Jupiter), Maven.

**Spec:** `docs/superpowers/specs/2026-08-13-sliding-window-context-design.md`

---

## File Structure

**Create (main):**
- `src/main/java/com/mrsmith/config/ContextStrategy.java` — `FULL`/`SLIDING` enum with `parse`
- `src/main/java/com/mrsmith/chat/SlidingWindowContextBuilder.java` — bounded-window builder
- `src/main/java/com/mrsmith/chat/ContextBuilderFactory.java` — `ContextBuilder create(AgentRuntime)` seam
- `src/main/java/com/mrsmith/chat/ContextBuilders.java` — `create(AgentRuntime)` + `windowBudget(AgentRuntime)`

**Modify (main):**
- `src/main/java/com/mrsmith/provider/TokenEstimator.java` — add `estimateMessageTokens(ChatMessage)`
- `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java` — `estimateUsage` reuses `estimateMessageTokens`
- `src/main/java/com/mrsmith/chat/ContextBuilder.java` — `start` gains `windowBudgetTokens`
- `src/main/java/com/mrsmith/chat/FullContextBuilder.java` — implement `start(String, int)`
- `src/main/java/com/mrsmith/config/AgentConfig.java` — add `contextBuilder` field
- `src/main/java/com/mrsmith/config/AgentRuntime.java` — `Globals` gains `contextWindowRatio`
- `src/main/java/com/mrsmith/config/AgentCatalog.java` — add `contextWindowRatio` field
- `src/main/java/com/mrsmith/config/ConfigLoader.java` — parse strategy/ratio; bake per-agent strategy
- `src/main/java/com/mrsmith/config/CliConfig.java` — add `contextBuilder`, `contextWindowRatio`
- `src/main/java/com/mrsmith/cli/ChatCommand.java` — new flags; inject `ContextBuilders::create`
- `src/main/java/com/mrsmith/chat/ChatSession.java` — hold factory; recreate builder; pass budget
- `src/main/java/com/mrsmith/chat/SubAgentRunner.java` — use `ContextBuilders.create` + budget

**Modify (test):**
- `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`
- `src/test/java/com/mrsmith/provider/TokenEstimatorTest.java`
- `src/test/java/com/mrsmith/cli/ChatCommandTest.java`
- `src/test/java/com/mrsmith/chat/FullContextBuilderTest.java` (unchanged — default method keeps it compiling)
- `src/test/java/com/mrsmith/chat/ChatSessionTest.java`
- `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java`
- Create: `src/test/java/com/mrsmith/chat/SlidingWindowContextBuilderTest.java`

**Modify (docs):**
- `README.md` — document `contextBuilder`/`contextWindowRatio` fields, flags, and precedence

---

### Task 1: `ContextStrategy` enum

**Files:**
- Create: `src/main/java/com/mrsmith/config/ContextStrategy.java`
- Test: `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`:

```java
    @Test
    void contextStrategyParseIsCaseInsensitive() {
        assertEquals(ContextStrategy.FULL, ContextStrategy.parse("full"));
        assertEquals(ContextStrategy.FULL, ContextStrategy.parse("FULL"));
        assertEquals(ContextStrategy.SLIDING, ContextStrategy.parse("sliding"));
        assertEquals(ContextStrategy.SLIDING, ContextStrategy.parse(" Sliding "));
        assertEquals(ContextStrategy.FULL, ContextStrategy.parse(null));
        assertEquals(ContextStrategy.FULL, ContextStrategy.parse("  "));
    }

    @Test
    void contextStrategyParseRejectsUnknown() {
        ConfigException e = assertThrows(ConfigException.class, () -> ContextStrategy.parse("bogus"));
        assertTrue(e.getMessage().contains("contextBuilder"));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=ConfigLoaderTest test`
Expected: FAIL — cannot resolve `ContextStrategy`.

- [ ] **Step 3: Create `ContextStrategy`**

`src/main/java/com/mrsmith/config/ContextStrategy.java`:

```java
package com.mrsmith.config;

import java.util.Locale;

public enum ContextStrategy {
    FULL, SLIDING;

    public static ContextStrategy parse(String value) {
        if (value == null || value.isBlank()) {
            return FULL;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "full" -> FULL;
            case "sliding" -> SLIDING;
            default -> throw new ConfigException("Unknown contextBuilder: " + value
                    + " (expected \"full\" or \"sliding\")");
        };
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=ConfigLoaderTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/config/ContextStrategy.java src/test/java/com/mrsmith/config/ConfigLoaderTest.java
git commit -m "feat: add ContextStrategy enum for context builder selection"
```

---

### Task 2: `TokenEstimator.estimateMessageTokens` + provider reuse

**Files:**
- Modify: `src/main/java/com/mrsmith/provider/TokenEstimator.java`
- Modify: `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java`
- Test: `src/test/java/com/mrsmith/provider/TokenEstimatorTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/mrsmith/provider/TokenEstimatorTest.java` (add imports for `ChatMessage`, `Role`, `ToolCall`, `ObjectMapper`, `List`):

```java
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void messageEstimateEqualsContentEstimateForPlainMessage() {
        assertEquals(1, TokenEstimator.estimateMessageTokens(new ChatMessage(Role.USER, "abcd")));
        assertEquals(0, TokenEstimator.estimateMessageTokens(new ChatMessage(Role.USER, null)));
    }

    @Test
    void messageEstimateCountsToolCallParts() throws Exception {
        ChatMessage msg = new ChatMessage(Role.ASSISTANT, null, null,
                List.of(new ToolCall("c1", "t", JSON.readTree("{}"))), null);
        assertEquals(3, TokenEstimator.estimateMessageTokens(msg));
    }

    @Test
    void messageEstimateCountsToolResultIdAndContent() {
        ChatMessage msg = new ChatMessage(Role.TOOL, "r", null, null, "c1");
        assertEquals(2, TokenEstimator.estimateMessageTokens(msg));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=TokenEstimatorTest test`
Expected: FAIL — `estimateMessageTokens` does not exist.

- [ ] **Step 3: Implement `estimateMessageTokens`**

In `src/main/java/com/mrsmith/provider/TokenEstimator.java`, add:

```java
    public static int estimateMessageTokens(ChatMessage message) {
        int tokens = estimateTokens(message.content());
        if (message.toolCalls() != null) {
            for (ToolCall call : message.toolCalls()) {
                tokens += estimateTokens(call.id());
                tokens += estimateTokens(call.name());
                tokens += call.arguments() == null ? 0 : estimateTokens(call.arguments().toString());
            }
        }
        if (message.toolCallId() != null) {
            tokens += estimateTokens(message.toolCallId());
        }
        return tokens;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=TokenEstimatorTest test`
Expected: PASS.

- [ ] **Step 5: Reuse it in the provider fallback estimate**

In `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java`, change `estimateUsage` to use `estimateMessageTokens`:

```java
    private Usage estimateUsage(List<ChatMessage> context, String replyContent, String thinking) {
        int prompt = 0;
        for (ChatMessage message : context) {
            prompt += TokenEstimator.estimateMessageTokens(message);
        }
        int completion = TokenEstimator.estimateTokens(replyContent);
        if (thinking != null) {
            completion += TokenEstimator.estimateTokens(thinking);
        }
        return new Usage(prompt, completion);
    }
```

- [ ] **Step 6: Run the provider tests to verify no regression**

Run: `mvn -q -Dtest=OpenAiCompatibleProviderTest test`
Expected: PASS (plain-message estimates are unchanged; no existing test asserts tool-call prompt estimates).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mrsmith/provider/TokenEstimator.java src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java src/test/java/com/mrsmith/provider/TokenEstimatorTest.java
git commit -m "feat: add per-message token estimation and reuse it in provider fallback"
```

---

### Task 3: `ContextBuilder.start` budget parameter

**Files:**
- Modify: `src/main/java/com/mrsmith/chat/ContextBuilder.java`
- Modify: `src/main/java/com/mrsmith/chat/FullContextBuilder.java`

- [ ] **Step 1: Change the interface**

`src/main/java/com/mrsmith/chat/ContextBuilder.java` — replace the `start` declaration with a budget-carrying abstract method plus a backward-compatible default:

```java
public interface ContextBuilder {

    default void start(String systemPrompt) {
        start(systemPrompt, 0);
    }

    void start(String systemPrompt, int windowBudgetTokens);

    void appendUser(String content);

    void appendAssistant(String content);

    void appendAssistantToolCalls(List<ToolCall> toolCalls);

    void appendToolResult(String toolCallId, String content);

    void appendSystem(String content);

    List<ChatMessage> messages();
}
```

- [ ] **Step 2: Update `FullContextBuilder`**

`src/main/java/com/mrsmith/chat/FullContextBuilder.java` — replace the `start(String)` method with:

```java
    @Override
    public void start(String systemPrompt, int windowBudgetTokens) {
        context.clear();
        if (systemPrompt != null) {
            context.add(new ChatMessage(Role.SYSTEM, systemPrompt));
        }
    }
```

- [ ] **Step 3: Run tests to verify they pass**

Run: `mvn -q -Dtest=FullContextBuilderTest test`
Expected: PASS (the default `start(String)` delegates to `start(prompt, 0)`).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ContextBuilder.java src/main/java/com/mrsmith/chat/FullContextBuilder.java
git commit -m "feat: add window budget parameter to ContextBuilder.start"
```

---

### Task 4: `SlidingWindowContextBuilder`

**Files:**
- Create: `src/main/java/com/mrsmith/chat/SlidingWindowContextBuilder.java`
- Create: `src/test/java/com/mrsmith/chat/SlidingWindowContextBuilderTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/mrsmith/chat/SlidingWindowContextBuilderTest.java`:

```java
package com.mrsmith.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Role;
import com.mrsmith.provider.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlidingWindowContextBuilderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void startSeedsSystemPrompt() {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start("You are helpful", 1000);
        List<ChatMessage> context = builder.messages();
        assertEquals(1, context.size());
        assertEquals(Role.SYSTEM, context.get(0).role());
        assertEquals("You are helpful", context.get(0).content());
    }

    @Test
    void underBudgetAccumulatesEverything() {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start(null, 1000);
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
    void dropsOldestTurnWhenOverBudget() {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start(null, 5);
        builder.appendUser("1111");       // 1 token
        builder.appendAssistant("22222222"); // 2 tokens (turn 1 = 3 tokens)
        builder.appendUser("33333333");   // 2 tokens (total 5)
        builder.appendAssistant("4444");  // 1 token -> total 6 > 5, drop turn 1
        List<ChatMessage> context = builder.messages();
        assertEquals(2, context.size());
        assertEquals("33333333", context.get(0).content());
        assertEquals("4444", context.get(1).content());
    }

    @Test
    void keepsCurrentTurnEvenWhenAloneOverBudget() {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start(null, 1);
        builder.appendUser("aaaaaaaaaaaaaaaa"); // 4 tokens > budget, single turn
        assertEquals(1, builder.messages().size());
    }

    @Test
    void systemMessagesArePinned() {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start("sys", 2);          // "sys" = 1 token
        builder.appendUser("aaaa");        // 1 token
        builder.appendAssistant("aaaaaaaa"); // 2 tokens -> over budget, but single turn
        List<ChatMessage> context = builder.messages();
        assertEquals(3, context.size());
        assertEquals(Role.SYSTEM, context.get(0).role());
        assertEquals("sys", context.get(0).content());
    }

    @Test
    void dropsWholeTurnIncludingToolCallAndResult() throws Exception {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start(null, 5);
        builder.appendUser("u1");          // 1 token
        builder.appendAssistantToolCalls(List.of(new ToolCall("c1", "t", JSON.readTree("{}")))); // 3 tokens
        builder.appendToolResult("c1", "r"); // 2 tokens -> total 6, single turn, no trim
        builder.appendUser("u2");          // 1 token -> 2 turns, trim turn 1
        List<ChatMessage> context = builder.messages();
        assertEquals(1, context.size());
        assertEquals(Role.USER, context.get(0).role());
        assertEquals("u2", context.get(0).content());
    }

    @Test
    void startResetsTheWindow() {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start(null, 1000);
        builder.appendUser("one");
        builder.start("sys", 1000);
        List<ChatMessage> context = builder.messages();
        assertEquals(1, context.size());
        assertEquals("sys", context.get(0).content());
    }

    @Test
    void zeroBudgetFallsBackToDefaultBudget() {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start(null, 0);
        builder.appendUser("hello");
        builder.appendAssistant("hi");
        assertEquals(2, builder.messages().size());
    }

    @Test
    void messagesIsImmutable() {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start(null, 1000);
        builder.appendUser("hello");
        List<ChatMessage> context = builder.messages();
        assertThrows(UnsupportedOperationException.class,
                () -> context.add(new ChatMessage(Role.USER, "x")));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=SlidingWindowContextBuilderTest test`
Expected: FAIL — cannot resolve `SlidingWindowContextBuilder`.

- [ ] **Step 3: Create `SlidingWindowContextBuilder`**

`src/main/java/com/mrsmith/chat/SlidingWindowContextBuilder.java`:

```java
package com.mrsmith.chat;

import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Role;
import com.mrsmith.provider.TokenEstimator;
import com.mrsmith.provider.ToolCall;

import java.util.ArrayList;
import java.util.List;

public class SlidingWindowContextBuilder implements ContextBuilder {

    public static final int DEFAULT_BUDGET = 100_000;

    private final List<ChatMessage> system = new ArrayList<>();
    private final List<ChatMessage> turns = new ArrayList<>();

    private int systemTokens;
    private int turnTokens;
    private int budget = DEFAULT_BUDGET;

    @Override
    public void start(String systemPrompt, int windowBudgetTokens) {
        system.clear();
        turns.clear();
        systemTokens = 0;
        turnTokens = 0;
        budget = windowBudgetTokens > 0 ? windowBudgetTokens : DEFAULT_BUDGET;
        if (systemPrompt != null) {
            addSystem(new ChatMessage(Role.SYSTEM, systemPrompt));
        }
    }

    @Override
    public void appendSystem(String content) {
        addSystem(new ChatMessage(Role.SYSTEM, content));
    }

    @Override
    public void appendUser(String content) {
        addTurn(new ChatMessage(Role.USER, content));
    }

    @Override
    public void appendAssistant(String content) {
        addTurn(new ChatMessage(Role.ASSISTANT, content));
    }

    @Override
    public void appendAssistantToolCalls(List<ToolCall> toolCalls) {
        addTurn(new ChatMessage(Role.ASSISTANT, null, null, List.copyOf(toolCalls), null));
    }

    @Override
    public void appendToolResult(String toolCallId, String content) {
        addTurn(new ChatMessage(Role.TOOL, content, null, null, toolCallId));
    }

    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> result = new ArrayList<>(system.size() + turns.size());
        result.addAll(system);
        result.addAll(turns);
        return List.copyOf(result);
    }

    private void addSystem(ChatMessage message) {
        system.add(message);
        systemTokens += TokenEstimator.estimateMessageTokens(message);
    }

    private void addTurn(ChatMessage message) {
        turns.add(message);
        turnTokens += TokenEstimator.estimateMessageTokens(message);
        trim();
    }

    private void trim() {
        while (systemTokens + turnTokens > budget && userMessageCount() > 1) {
            int drop = indexOfSecondUser();
            for (int i = 0; i < drop; i++) {
                turnTokens -= TokenEstimator.estimateMessageTokens(turns.get(i));
            }
            turns.subList(0, drop).clear();
        }
    }

    private int userMessageCount() {
        int count = 0;
        for (ChatMessage message : turns) {
            if (message.role() == Role.USER) {
                count++;
            }
        }
        return count;
    }

    private int indexOfSecondUser() {
        int seen = 0;
        for (int i = 0; i < turns.size(); i++) {
            if (turns.get(i).role() == Role.USER) {
                seen++;
                if (seen == 2) {
                    return i;
                }
            }
        }
        return turns.size();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=SlidingWindowContextBuilderTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/chat/SlidingWindowContextBuilder.java src/test/java/com/mrsmith/chat/SlidingWindowContextBuilderTest.java
git commit -m "feat: add sliding-window context builder"
```

---

### Task 5: Config record fields (`AgentConfig`, `Globals`, `AgentCatalog`)

**Files:**
- Modify: `src/main/java/com/mrsmith/config/AgentConfig.java`
- Modify: `src/main/java/com/mrsmith/config/AgentRuntime.java`
- Modify: `src/main/java/com/mrsmith/config/AgentCatalog.java`

- [ ] **Step 1: Add `contextBuilder` to `AgentConfig`**

Replace `src/main/java/com/mrsmith/config/AgentConfig.java` with (appending `ContextStrategy contextBuilder` as the 11th field; every convenience constructor passes `ContextStrategy.FULL`):

```java
package com.mrsmith.config;

import java.util.List;

public record AgentConfig(String name, String provider, String model,
                          String systemPrompt, Integer maxContextTokens,
                          Integer maxToolRounds, Integer maxToolCallsPerSession,
                          List<String> tools,
                          List<String> shellHarmlessCommands,
                          List<String> shellDangerousCommands,
                          ContextStrategy contextBuilder) {

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens) {
        this(name, provider, model, systemPrompt, maxContextTokens, null, null,
                List.of(), List.of(), List.of(), ContextStrategy.FULL);
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, List<String> tools) {
        this(name, provider, model, systemPrompt, maxContextTokens, null, null,
                tools, List.of(), List.of(), ContextStrategy.FULL);
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, Integer maxToolRounds) {
        this(name, provider, model, systemPrompt, maxContextTokens, maxToolRounds, null,
                List.of(), List.of(), List.of(), ContextStrategy.FULL);
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, Integer maxToolRounds,
                       Integer maxToolCallsPerSession) {
        this(name, provider, model, systemPrompt, maxContextTokens, maxToolRounds,
                maxToolCallsPerSession, List.of(), List.of(), List.of(), ContextStrategy.FULL);
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, Integer maxToolRounds,
                       Integer maxToolCallsPerSession, List<String> tools) {
        this(name, provider, model, systemPrompt, maxContextTokens, maxToolRounds,
                maxToolCallsPerSession, tools, List.of(), List.of(), ContextStrategy.FULL);
    }
}
```

- [ ] **Step 2: Add `contextWindowRatio` to `Globals`**

Replace `src/main/java/com/mrsmith/config/AgentRuntime.java` with:

```java
package com.mrsmith.config;

public record AgentRuntime(AgentConfig agent, ProviderConfig provider, AgentRuntime.Globals globals) {

    public static final double DEFAULT_CONTEXT_WINDOW_RATIO = 0.75;

    public record Globals(boolean includeUsage, double contextWindowRatio) {

        public Globals(boolean includeUsage) {
            this(includeUsage, DEFAULT_CONTEXT_WINDOW_RATIO);
        }
    }
}
```

- [ ] **Step 3: Add `contextWindowRatio` to `AgentCatalog`**

In `src/main/java/com/mrsmith/config/AgentCatalog.java`:

1. Add the field:

```java
    private final double contextWindowRatio;
```

2. Change the 5-arg constructor to delegate with the default ratio and add the canonical 8-arg constructor:

```java
    public AgentCatalog(List<ProviderConfig> providers, List<AgentConfig> agents,
                        String defaultAgent, boolean includeUsage, Path sessionsDir) {
        this(providers, agents, defaultAgent, includeUsage, sessionsDir,
                defaultProjectSkillsDir(), defaultGlobalSkillsDir(),
                AgentRuntime.DEFAULT_CONTEXT_WINDOW_RATIO);
    }

    public AgentCatalog(List<ProviderConfig> providers, List<AgentConfig> agents,
                        String defaultAgent, boolean includeUsage, Path sessionsDir,
                        Path projectSkillsDir, Path globalSkillsDir, double contextWindowRatio) {
```

3. In the canonical constructor body, add the assignment:

```java
        this.contextWindowRatio = contextWindowRatio;
```

4. Update `resolve` to pass the ratio into `Globals`:

```java
        return new AgentRuntime(agent, provider, new AgentRuntime.Globals(includeUsage, contextWindowRatio));
```

- [ ] **Step 4: Run the full suite to verify nothing broke**

Run: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: BUILD SUCCESS (defaults preserve existing behavior; `ConfigLoader` still compiles because the removed 7-arg constructor is fixed in Task 6 — if the build fails to compile at this point, complete Task 6 Step 3 before running).

Note: `ConfigLoader.load` currently calls the 7-arg `AgentCatalog` constructor, which no longer exists. To keep the build green, either (a) run only the affected tests here and commit after Task 6, or (b) do Task 6 immediately. Prefer completing Task 6 before committing Task 5 if the full suite fails to compile.

- [ ] **Step 5: Commit (together with Task 6's ConfigLoader change if needed to compile)**

```bash
git add src/main/java/com/mrsmith/config/AgentConfig.java src/main/java/com/mrsmith/config/AgentRuntime.java src/main/java/com/mrsmith/config/AgentCatalog.java
git commit -m "feat: add context builder strategy and window ratio config fields"
```

---

### Task 6: `ConfigLoader` parsing + `CliConfig` fields

**Files:**
- Modify: `src/main/java/com/mrsmith/config/ConfigLoader.java`
- Modify: `src/main/java/com/mrsmith/config/CliConfig.java`
- Test: `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`

- [ ] **Step 1: Extend `CliConfig`**

Replace `src/main/java/com/mrsmith/config/CliConfig.java` with:

```java
package com.mrsmith.config;

import java.nio.file.Path;

public record CliConfig(String agent, Path sessionsDir, String contextBuilder, Double contextWindowRatio) {

    public CliConfig(String agent, Path sessionsDir) {
        this(agent, sessionsDir, null, null);
    }

    public static CliConfig empty() {
        return new CliConfig(null, null, null, null);
    }
}
```

- [ ] **Step 2: Write the failing tests**

Append to `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`:

```java
    @Test
    void contextBuilderDefaultsToFullPerAgent() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(ContextStrategy.FULL, catalog.resolve("a").agent().contextBuilder());
    }

    @Test
    void globalContextBuilderIsDefaultForAgentsWithoutOverride() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a",
                  "contextBuilder": "sliding"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(ContextStrategy.SLIDING, catalog.resolve("a").agent().contextBuilder());
    }

    @Test
    void perAgentContextBuilderOverridesGlobalDefault() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [
                    { "name": "a", "provider": "p", "model": "m", "contextBuilder": "sliding" },
                    { "name": "b", "provider": "p", "model": "m" }
                  ],
                  "defaultAgent": "a",
                  "contextBuilder": "full"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(ContextStrategy.SLIDING, catalog.resolve("a").agent().contextBuilder());
        assertEquals(ContextStrategy.FULL, catalog.resolve("b").agent().contextBuilder());
    }

    @Test
    void contextWindowRatioDefaultsAndParses() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(AgentRuntime.DEFAULT_CONTEXT_WINDOW_RATIO, catalog.resolve("a").globals().contextWindowRatio());
    }

    @Test
    void contextWindowRatioFromEnvOverridesFile() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a",
                  "contextWindowRatio": 0.5
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(),
                Map.of("MRSMITH_CONTEXT_WINDOW_RATIO", "0.8"));
        assertEquals(0.8, catalog.resolve("a").globals().contextWindowRatio());
    }

    @Test
    void invalidContextWindowRatioThrows() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a",
                  "contextWindowRatio": 1.5
                }
                """);
        assertThrows(ConfigException.class,
                () -> ConfigLoader.load(file, CliConfig.empty(), Map.of()));
    }

    @Test
    void invalidContextBuilderThrows() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m", "contextBuilder": "bogus" } ],
                  "defaultAgent": "a"
                }
                """);
        assertThrows(ConfigException.class,
                () -> ConfigLoader.load(file, CliConfig.empty(), Map.of()));
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn -q -Dtest=ConfigLoaderTest test`
Expected: FAIL — `contextBuilder()` and `contextWindowRatio()` not populated.

- [ ] **Step 4: Update `ConfigLoader`**

In `src/main/java/com/mrsmith/config/ConfigLoader.java`:

1. In `load(...)`, compute the default strategy and ratio before building the catalog, and pass them through:

```java
        List<ProviderConfig> providers = parseProviders(root, env);
        ContextStrategy defaultStrategy = ContextStrategy.parse(firstNonNull(
                cli.contextBuilder(),
                env.get("MRSMITH_CONTEXT_BUILDER"),
                root.hasNonNull("contextBuilder") ? root.get("contextBuilder").asText() : null));
        List<AgentConfig> agents = parseAgents(root, defaultStrategy);
        String defaultAgent = root.hasNonNull("defaultAgent") ? root.get("defaultAgent").asText() : null;
        boolean includeUsage = !root.hasNonNull("includeUsage") || root.get("includeUsage").asBoolean();
        double contextWindowRatio = parseRatio(firstNonNull(
                cli.contextWindowRatio() == null ? null : cli.contextWindowRatio().toString(),
                env.get("MRSMITH_CONTEXT_WINDOW_RATIO"),
                root.hasNonNull("contextWindowRatio") ? root.get("contextWindowRatio").asText() : null));
```

2. In the final `return new AgentCatalog(...)` call, append `contextWindowRatio`:

```java
        return new AgentCatalog(providers, agents, defaultAgent, includeUsage, Path.of(sessionsDir),
                projectSkillsDir, globalSkillsDir, contextWindowRatio);
```

3. Change `parseAgents` to accept and apply the default strategy:

```java
    private static List<AgentConfig> parseAgents(JsonNode root, ContextStrategy defaultStrategy) {
        List<AgentConfig> result = new ArrayList<>();
        JsonNode arr = root.path("agents");
        if (arr.isArray()) {
            for (JsonNode node : arr) {
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
                                : defaultStrategy));
            }
        }
        return result;
    }
```

4. Add the `parseRatio` helper (next to `firstNonNull`):

```java
    private static double parseRatio(String raw) {
        if (raw == null || raw.isBlank()) {
            return AgentRuntime.DEFAULT_CONTEXT_WINDOW_RATIO;
        }
        double value;
        try {
            value = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("Invalid contextWindowRatio: " + raw);
        }
        if (value <= 0 || value > 1) {
            throw new ConfigException("contextWindowRatio must be in (0, 1]: " + raw);
        }
        return value;
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -q -Dtest=ConfigLoaderTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/config/ConfigLoader.java src/main/java/com/mrsmith/config/CliConfig.java src/test/java/com/mrsmith/config/ConfigLoaderTest.java
git commit -m "feat: parse context builder strategy and window ratio from config"
```

---

### Task 7: `ContextBuilders` factory + budget helper

**Files:**
- Create: `src/main/java/com/mrsmith/chat/ContextBuilders.java`
- Create: `src/test/java/com/mrsmith/chat/ContextBuildersTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/mrsmith/chat/ContextBuildersTest.java`:

```java
package com.mrsmith.chat;

import com.mrsmith.config.AgentConfig;
import com.mrsmith.config.AgentRuntime;
import com.mrsmith.config.ContextStrategy;
import com.mrsmith.config.ProviderConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextBuildersTest {

    private static AgentRuntime runtime(String strategy, Integer maxContext, double ratio) {
        AgentConfig agent = new AgentConfig("a", "p", "m", null, maxContext, null, null,
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                ContextStrategy.parse(strategy));
        ProviderConfig provider = new ProviderConfig("p", "sk", "https://example.com/v1");
        return new AgentRuntime(agent, provider, new AgentRuntime.Globals(true, ratio));
    }

    @Test
    void createReturnsFullForFullStrategy() {
        assertTrue(ContextBuilders.create(runtime("full", 128000, 0.75)) instanceof FullContextBuilder);
    }

    @Test
    void createReturnsSlidingForSlidingStrategy() {
        assertTrue(ContextBuilders.create(runtime("sliding", 128000, 0.75)) instanceof SlidingWindowContextBuilder);
    }

    @Test
    void windowBudgetUsesRatioOfMaxContext() {
        assertEquals(96000, ContextBuilders.windowBudget(runtime("sliding", 128000, 0.75)));
    }

    @Test
    void windowBudgetRoundsToNearestToken() {
        assertEquals(5, ContextBuilders.windowBudget(runtime("sliding", 6, 0.75)));
    }

    @Test
    void windowBudgetFallsBackToDefaultBudgetWhenUnset() {
        assertEquals(75000, ContextBuilders.windowBudget(runtime("sliding", null, 0.75)));
        assertEquals(75000, ContextBuilders.windowBudget(runtime("sliding", 0, 0.75)));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=ContextBuildersTest test`
Expected: FAIL — cannot resolve `ContextBuilders`.

- [ ] **Step 3: Create `ContextBuilders`**

`src/main/java/com/mrsmith/chat/ContextBuilders.java`:

```java
package com.mrsmith.chat;

import com.mrsmith.config.AgentRuntime;
import com.mrsmith.config.ContextStrategy;

public final class ContextBuilders {

    private ContextBuilders() {
    }

    public static ContextBuilder create(AgentRuntime runtime) {
        ContextStrategy strategy = runtime.agent().contextBuilder();
        if (strategy == ContextStrategy.SLIDING) {
            return new SlidingWindowContextBuilder();
        }
        return new FullContextBuilder();
    }

    public static int windowBudget(AgentRuntime runtime) {
        Integer maxContext = runtime.agent().maxContextTokens();
        int base = (maxContext != null && maxContext > 0)
                ? maxContext
                : SlidingWindowContextBuilder.DEFAULT_BUDGET;
        return (int) Math.round(base * runtime.globals().contextWindowRatio());
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=ContextBuildersTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ContextBuilders.java src/test/java/com/mrsmith/chat/ContextBuildersTest.java
git commit -m "feat: add ContextBuilders factory and budget helper"
```

---

### Task 8: `ChatCommand` CLI flags

**Files:**
- Modify: `src/main/java/com/mrsmith/cli/ChatCommand.java`
- Test: `src/test/java/com/mrsmith/cli/ChatCommandTest.java`

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/mrsmith/cli/ChatCommandTest.java`:

```java
    @Test
    void helpListsContextBuilderFlags() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = new CommandLine(new ChatCommand()).execute("--help");
            assertEquals(0, exit);
            assertTrue(out.toString().contains("--context-builder"));
            assertTrue(out.toString().contains("--context-window-ratio"));
        } finally {
            System.setOut(original);
        }
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -Dtest=ChatCommandTest test`
Expected: FAIL — flags not listed.

- [ ] **Step 3: Add the options and wire the factory**

In `src/main/java/com/mrsmith/cli/ChatCommand.java`:

1. Add the imports (replace the `ContextBuilder`/`FullContextBuilder` imports):

```java
import com.mrsmith.chat.ChatSession;
import com.mrsmith.chat.ContextBuilderFactory;
import com.mrsmith.chat.ContextBuilders;
```

2. Add the options after `--sessions-dir`:

```java
    @Option(names = "--context-builder", description = "Context strategy: full or sliding (default full).")
    private String contextBuilder;

    @Option(names = "--context-window-ratio", description = "Fraction of the context limit to keep in a sliding window (default 0.75).")
    private Double contextWindowRatio;
```

3. Change the `CliConfig` construction to pass the new fields:

```java
            catalog = ConfigLoader.load(new CliConfig(agent, sessionsDir, contextBuilder, contextWindowRatio));
```

4. Change the `ContextBuilder` construction to a factory:

```java
        ContextBuilderFactory contextBuilderFactory = ContextBuilders::create;
```

5. Change the `ChatSession` construction to pass the factory:

```java
        ChatSession session = new ChatSession(io, transcripts, contextBuilderFactory, catalog,
                OpenAiCompatibleProvider::new,
                (runtime, skillCatalog, terminalIo, taskRunner) -> ToolRegistry.with(
                        runtime.agent().tools(), skillCatalog, terminalIo, taskRunner,
                        new ShellConfig(runtime.agent().shellHarmlessCommands(),
                                runtime.agent().shellDangerousCommands())),
                skills, initialAgent);
```

- [ ] **Step 4: Run tests to verify they pass**

Note: `ChatSession` still accepts a `ContextBuilder` (Task 9 changes it). Until Task 9, this step will NOT compile because `ChatCommand` now passes a `ContextBuilderFactory`. Complete Task 9 before running this test to green.

- [ ] **Step 5: Commit (after Task 9)**

```bash
git add src/main/java/com/mrsmith/cli/ChatCommand.java src/test/java/com/mrsmith/cli/ChatCommandTest.java
git commit -m "feat: add context builder CLI flags"
```

---

### Task 9: `ContextBuilderFactory` + wire `ChatSession`

**Files:**
- Create: `src/main/java/com/mrsmith/chat/ContextBuilderFactory.java`
- Modify: `src/main/java/com/mrsmith/chat/ChatSession.java`
- Test: `src/test/java/com/mrsmith/chat/ChatSessionTest.java`

- [ ] **Step 1: Create `ContextBuilderFactory`**

`src/main/java/com/mrsmith/chat/ContextBuilderFactory.java`:

```java
package com.mrsmith.chat;

import com.mrsmith.config.AgentRuntime;

@FunctionalInterface
public interface ContextBuilderFactory {

    ContextBuilder create(AgentRuntime runtime);

    static ContextBuilderFactory full() {
        return runtime -> new FullContextBuilder();
    }
}
```

- [ ] **Step 2: Modify `ChatSession`**

In `src/main/java/com/mrsmith/chat/ChatSession.java`:

1. Change the field declaration (replace the `contextBuilder` final field):

```java
    private final ContextBuilderFactory contextBuilderFactory;
    ...
    private ContextBuilder contextBuilder;
```

2. Change the constructor signature and body:

```java
    public ChatSession(IO io, TranscriptWriter transcripts, ContextBuilderFactory contextBuilderFactory,
                       AgentCatalog agents, ProviderFactory providerFactory,
                       ToolRegistryFactory toolRegistryFactory, SkillCatalog skills,
                       String initialAgentName) {
        this.io = io;
        this.transcripts = transcripts;
        this.contextBuilderFactory = contextBuilderFactory;
        ...
```

3. In `startFreshSession()`, recreate the builder and pass the budget (replace the existing `contextBuilder.start(...)` line):

```java
        contextBuilder = contextBuilderFactory.create(runtime);
        contextBuilder.start(composeSystemPrompt(runtime.agent().systemPrompt()),
                ContextBuilders.windowBudget(runtime));
```

- [ ] **Step 3: Update the test call sites**

In `src/test/java/com/mrsmith/chat/ChatSessionTest.java`, replace **every** occurrence of `new FullContextBuilder()` with `ContextBuilderFactory.full()` (37 occurrences: 36 inline tests + the `session(...)` helper at the end of the file). The 3rd constructor argument is now a `ContextBuilderFactory`.

- [ ] **Step 4: Add the recording stub and new tests**

Add this stub builder to `src/test/java/com/mrsmith/chat/ChatSessionTest.java` (next to the other fake classes):

```java
    static class RecordingContextBuilder implements ContextBuilder {
        final FullContextBuilder delegate = new FullContextBuilder();
        int startBudget = -1;
        String startPrompt;

        @Override
        public void start(String systemPrompt, int windowBudgetTokens) {
            startPrompt = systemPrompt;
            startBudget = windowBudgetTokens;
            delegate.start(systemPrompt, windowBudgetTokens);
        }

        @Override
        public void appendUser(String content) {
            delegate.appendUser(content);
        }

        @Override
        public void appendAssistant(String content) {
            delegate.appendAssistant(content);
        }

        @Override
        public void appendAssistantToolCalls(List<ToolCall> toolCalls) {
            delegate.appendAssistantToolCalls(toolCalls);
        }

        @Override
        public void appendToolResult(String toolCallId, String content) {
            delegate.appendToolResult(toolCallId, content);
        }

        @Override
        public void appendSystem(String content) {
            delegate.appendSystem(content);
        }

        @Override
        public List<ChatMessage> messages() {
            return delegate.messages();
        }
    }
```

Add the two tests (append near the other session tests):

```java
    @Test
    void passesWindowBudgetToStart() throws Exception {
        RecordingContextBuilder builder = new RecordingContextBuilder();
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, r -> builder,
                catalog(null, 128000), new FakeProviderFactory(provider),
                noToolsFactory(), emptySkills(), "a");
        session.run();
        assertEquals(96000, builder.startBudget);
    }

    @Test
    void recreatesBuilderWithNewAgentsBudgetOnSwitch() throws Exception {
        List<RecordingContextBuilder> builders = new ArrayList<>();
        AgentCatalog catalog = new AgentCatalog(
                List.of(new ProviderConfig("p", "sk-test", "https://example.com/v1")),
                List.of(new AgentConfig("a", "p", "m", null, 100000),
                        new AgentConfig("b", "p", "m", null, 200000)),
                "a", true, Path.of("sessions"));
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/agent b", "hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, r -> {
            RecordingContextBuilder b = new RecordingContextBuilder();
            builders.add(b);
            return b;
        }, catalog, new FakeProviderFactory(provider), noToolsFactory(), emptySkills(), "a");
        session.run();
        assertEquals(2, builders.size());
        assertEquals(150000, builders.get(1).startBudget);
    }
```

- [ ] **Step 5: Run the full suite to verify it passes**

Run: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: BUILD SUCCESS (this also greens Task 8's `ChatCommandTest`, which now compiles).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ContextBuilderFactory.java src/main/java/com/mrsmith/chat/ChatSession.java src/test/java/com/mrsmith/chat/ChatSessionTest.java
git commit -m "feat: recreate context builder per agent via factory"
```

Then, to keep history clean, also commit Task 8's `ChatCommand` change here if it was not committed in Task 8:

```bash
git add src/main/java/com/mrsmith/cli/ChatCommand.java src/test/java/com/mrsmith/cli/ChatCommandTest.java
git commit -m "feat: add context builder CLI flags"
```

---

### Task 10: Wire `SubAgentRunner`

**Files:**
- Modify: `src/main/java/com/mrsmith/chat/SubAgentRunner.java`
- Test: `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java` (add import `com.mrsmith.config.ContextStrategy`):

```java
    @Test
    void slidingSubAgentTrimsOldTurnsOnResume() throws Exception {
        AgentCatalog catalog = new AgentCatalog(
                List.of(new ProviderConfig("p", "sk-test", "https://example.com/v1")),
                List.of(new AgentConfig("a", "p", "m", "sys", 6, null, null,
                        List.of(), List.of(), List.of(), ContextStrategy.SLIDING)),
                "a", true, tempDir);
        Files.createDirectories(tempDir.resolve(sessionId.toString()));
        FakeProvider provider = new FakeProvider();
        ToolRegistry tools = new ToolRegistry(List.of());
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRunner.Context(catalog,
                new FakeProviderFactory(provider), fixedRegistry(tools), emptySkills(),
                new StubIo(List.of()), new UsageTracker(), () -> catalog.resolve("a"),
                () -> sessionId, () -> new ToolBudget(null, new StubIo(List.of())),
                () -> new ToolApproval()));
        runner.run("first", null, null);
        runner.run("continue", null, "subagent-1");
        List<ChatMessage> second = provider.receivedHistories.get(1);
        assertEquals(2, second.size());
        assertEquals(Role.SYSTEM, second.get(0).role());
        assertEquals("sys", second.get(0).content());
        assertEquals(Role.USER, second.get(1).role());
        assertEquals("continue", second.get(1).content());
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -Dtest=SubAgentRunnerTest#slidingSubAgentTrimsOldTurnsOnResume test`
Expected: FAIL — the second context still contains the untrimmed "first" turn (3 messages, not 2).

- [ ] **Step 3: Modify `SubAgentRunner`**

In `src/main/java/com/mrsmith/chat/SubAgentRunner.java`:

1. Change the builder creation and start call in `run(...)`:

```java
        ContextBuilder context = ContextBuilders.create(config);
        context.start(config.agent().systemPrompt(), ContextBuilders.windowBudget(config));
```

2. Change `sinkFor` signature and body type:

```java
    private ToolLoop.Sink sinkFor(ContextBuilder context, TranscriptWriter transcripts) {
```

3. Change `replay` signature and body type:

```java
    private static void replay(ContextBuilder context, ChatMessage message) {
```

- [ ] **Step 4: Run the full suite to verify it passes**

Run: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/chat/SubAgentRunner.java src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java
git commit -m "feat: use sliding-window context builder in sub-agents"
```

---

### Task 11: Update docs

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Document the config fields**

In `README.md`:

1. In the example config JSON (the `agents` block and top-level), add `contextBuilder` and `contextWindowRatio`. E.g.:

```json
      "maxContextTokens": 128000,
      "maxToolRounds": 32,
      "maxToolCallsPerSession": 500,
      "contextBuilder": "sliding",
      "tools": ["shell", "read_file", "write_file", "list_dir", "glob", "web_fetch"]
```

and after `"includeUsage": true,` add:

```json
  "contextWindowRatio": 0.75,
```

2. In the **Fields** table, add rows after `agents[].shellDangerousCommands`:

```markdown
| `agents[].contextBuilder` | Context strategy for this agent: `full` (default) or `sliding`; overrides the global default (optional) |
| `contextBuilder` | Global default context strategy (`full` or `sliding`), applied to agents without their own override (optional, default `full`) |
| `contextWindowRatio` | Fraction of the agent's `maxContextTokens` to keep in a sliding window (optional, default `0.75`, must be in `(0, 1]`) |
```

3. In the **Precedence** paragraph, add a sentence: CLI flags `--context-builder`/`--context-window-ratio` and env vars `MRSMITH_CONTEXT_BUILDER`/`MRSMITH_CONTEXT_WINDOW_RATIO` set the global default strategy and the ratio (CLI > env > file > defaults).

4. In the feature summary near the top (the "Context awareness" bullet), add a short sentence noting that agents can use a sliding-window context builder to auto-trim the oldest turns within a token budget.

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: document context builder strategy and window ratio"
```

---

## Self-Review Notes

- **Spec coverage:** strategy enum (Task 1), token estimation (Task 2), budget-carrying `start` (Task 3), sliding builder algorithm incl. pinning/atomicity (Task 4), config records (Task 5), config parsing + validation (Task 6), factory + budget (Task 7), CLI flags (Task 8), ChatSession recreation + budget (Task 9), sub-agents (Task 10), docs (Task 11).
- **Type consistency:** `ContextStrategy.parse` (Tasks 1/6/7), `AgentConfig.contextBuilder()` (Tasks 5/6/7), `Globals.contextWindowRatio()` (Tasks 5/6/7), `SlidingWindowContextBuilder.DEFAULT_BUDGET` (Tasks 4/7), `ContextBuilders.create/windowBudget` (Tasks 7/9/10), `ContextBuilderFactory` (Tasks 8/9).
- **Compile boundaries:** Task 5 changes the `AgentCatalog` constructor used by `ConfigLoader`; complete Task 6 before expecting a green full build. Task 8 changes `ChatCommand` to a factory that `ChatSession` doesn't accept until Task 9; complete Task 9 before greening `ChatCommandTest`.
