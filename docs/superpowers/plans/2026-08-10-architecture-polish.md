# Architecture Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the registry-drift fix and six small cleanups (shared ObjectMapper, atomic file writes, unified usage accounting, SubAgentRunner context record, centralized warnings) with no observable behavior change.

**Architecture:** New `com.mrsmith.util` package holds `Json` (single shared `ObjectMapper`) and `Warn` (centralized warning print). `ToolLoop`/`UsageTracker` share a new `chat.UsageAccumulator`. `tool.AtomicFiles.write` gives `edit`/`write_file` temp-file-plus-atomic-move writes. `SubAgentRunner` gains a nested `Context` record and builds its registry through `ToolRegistryFactory` instead of a direct `ToolRegistry.with(...)`. Spec: `docs/superpowers/specs/2026-08-10-architecture-polish-design.md`.

**Tech Stack:** Java 21, JUnit 5 (Jupiter), Maven.

---

## File Structure

**Create (main):**
- `src/main/java/com/mrsmith/util/Json.java` — shared `ObjectMapper.MAPPER`
- `src/main/java/com/mrsmith/util/Warn.java` — `warn(String)`
- `src/main/java/com/mrsmith/tool/AtomicFiles.java` — atomic `write(Path, byte[])`
- `src/main/java/com/mrsmith/chat/UsageAccumulator.java` — usage accumulation shared by `ToolLoop` and `UsageTracker`

**Create (test):**
- `src/test/java/com/mrsmith/util/JsonTest.java`, `src/test/java/com/mrsmith/util/WarnTest.java`
- `src/test/java/com/mrsmith/tool/AtomicFilesTest.java`
- `src/test/java/com/mrsmith/chat/UsageAccumulatorTest.java`

**Modify (main):**
- 16 files switch `new ObjectMapper()` → `Json.MAPPER` (listed in Task 2)
- `EditTool.java`, `WriteFileTool.java` — atomic writes (Task 4)
- `ToolLoop.java`, `UsageTracker.java` — shared `UsageAccumulator` (Task 5)
- `SubAgentRunner.java`, `ChatSession.java` — `Context` record + registry-drift fix (Task 6)
- Warning sites in `ChatSession.java`, `SubAgentRunner.java`, `SkillCatalog.java`, `SseParser.java` (Task 3)

**Modify (test):**
- `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java` — constructor call sites (Task 6)

**Modify (docs):**
- `README.md` — architecture table gains a `util` row (Task 7)

---

### Task 1: `com.mrsmith.util` — `Json` and `Warn`

**Files:**
- Create: `src/main/java/com/mrsmith/util/Json.java`
- Create: `src/main/java/com/mrsmith/util/Warn.java`
- Create: `src/test/java/com/mrsmith/util/JsonTest.java`
- Create: `src/test/java/com/mrsmith/util/WarnTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/mrsmith/util/JsonTest.java`:

```java
package com.mrsmith.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class JsonTest {

    @Test
    void providesSharedMapper() {
        assertNotNull(Json.MAPPER);
    }
}
```

Create `src/test/java/com/mrsmith/util/WarnTest.java`:

```java
package com.mrsmith.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WarnTest {

    @Test
    void warnsWithPrefix() {
        PrintStream original = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            Warn.warn("boom");
        } finally {
            System.setErr(original);
        }
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Warning: boom"));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -Dtest=JsonTest,WarnTest test`
Expected: FAIL — compilation error, `Json` and `Warn` not defined.

- [ ] **Step 3: Implement**

Create `src/main/java/com/mrsmith/util/Json.java`:

```java
package com.mrsmith.util;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }
}
```

Create `src/main/java/com/mrsmith/util/Warn.java`:

```java
package com.mrsmith.util;

public final class Warn {

    private Warn() {
    }

    public static void warn(String message) {
        System.err.println("Warning: " + message);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -Dtest=JsonTest,WarnTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/util/Json.java src/main/java/com/mrsmith/util/Warn.java src/test/java/com/mrsmith/util/JsonTest.java src/test/java/com/mrsmith/util/WarnTest.java
git commit -m "feat: add Json and Warn utilities"
```

---

### Task 2: Shared ObjectMapper across main sources

**Files:**
- Modify (all 16): `session/TranscriptJson.java`, `tool/GlobTool.java`, `session/SubAgentTranscriptStore.java`, `tool/SkillTool.java`, `tool/ListDirTool.java`, `tool/TaskTool.java`, `config/ConfigLoader.java`, `tool/TodowriteTool.java`, `tool/ReadFileTool.java`, `provider/OpenAiCompatibleProvider.java`, `provider/SseParser.java`, `tool/WebFetchTool.java`, `tool/WriteFileTool.java`, `tool/ShellTool.java`, `tool/EditTool.java`, `tool/QuestionTool.java`

- [ ] **Step 1: Switch each file to `Json.MAPPER`**

For **every one** of the 16 files above, apply exactly two edits:

1. Change the field declaration:

```java
    private static final ObjectMapper JSON = new ObjectMapper();
```

to:

```java
    private static final ObjectMapper JSON = Json.MAPPER;
```

2. Add the import `import com.mrsmith.util.Json;` to the existing import block (alphabetical: after the `com.fasterxml.*`/`com.mrsmith.*` imports as appropriate — place it among the other `com.mrsmith` imports).

The `ObjectMapper` import already present in each file stays (it is still the field type). No other changes.

- [ ] **Step 2: Run the full suite**

Run: `mvn test 2>&1 | grep -E "Tests run: [0-9]+, Failures"`
Expected: BUILD SUCCESS — 299 tests pass (no behavior change; all mappers share one instance).

- [ ] **Step 3: Commit**

```bash
git add src/main/java
git commit -m "refactor: use a single shared ObjectMapper across main sources"
```

---

### Task 3: Centralize warnings via `Warn`

**Files:**
- Modify: `src/main/java/com/mrsmith/chat/ChatSession.java`
- Modify: `src/main/java/com/mrsmith/chat/SubAgentRunner.java`
- Modify: `src/main/java/com/mrsmith/skill/SkillCatalog.java`
- Modify: `src/main/java/com/mrsmith/provider/SseParser.java`

- [ ] **Step 1: Update ChatSession**

In `src/main/java/com/mrsmith/chat/ChatSession.java`, add the import `import com.mrsmith.util.Warn;` and replace each `System.err.println("Warning: ...")` with `Warn.warn(...)`, preserving the message text without the leading `"Warning: "` prefix:

1. Line ~180: `System.err.println("Warning: could not create session folder for " + id` … → `Warn.warn("could not create session folder for " + id` … (keep the `+ ": " + e.getMessage() + ". Session transcript disabled."` continuation).
2. Lines ~276, ~288, ~300, ~312, ~324: `System.err.println("Warning: could not write session transcript: " + e.getMessage())` → `Warn.warn("could not write session transcript: " + e.getMessage())` (5 occurrences).

- [ ] **Step 2: Update SubAgentRunner**

In `src/main/java/com/mrsmith/chat/SubAgentRunner.java`, add `import com.mrsmith.util.Warn;` and replace the two `System.err.println("Warning: could not write subagent transcript: " + e.getMessage())` lines (in the `sinkFor` anonymous class) with `Warn.warn("could not write subagent transcript: " + e.getMessage())`.

- [ ] **Step 3: Update SkillCatalog**

In `src/main/java/com/mrsmith/skill/SkillCatalog.java`, add `import com.mrsmith.util.Warn;` and change the private `warn` helper body:

```java
    private static void warn(String message) {
        Warn.warn(message);
    }
```

- [ ] **Step 4: Update SseParser**

In `src/main/java/com/mrsmith/provider/SseParser.java`, add `import com.mrsmith.util.Warn;` and replace:

```java
            System.err.println("Warning: malformed SSE chunk, skipping: " + payload);
```

with:

```java
            Warn.warn("malformed SSE chunk, skipping: " + payload);
```

- [ ] **Step 5: Run the full suite**

Run: `mvn test 2>&1 | grep -E "Tests run: [0-9]+, Failures"`
Expected: BUILD SUCCESS — 299 tests pass (output text unchanged, so stderr-asserting tests keep passing).

- [ ] **Step 6: Commit**

```bash
git add src/main/java
git commit -m "refactor: centralize warning messages in Warn utility"
```

---

### Task 4: Atomic file writes

**Files:**
- Create: `src/main/java/com/mrsmith/tool/AtomicFiles.java`
- Create: `src/test/java/com/mrsmith/tool/AtomicFilesTest.java`
- Modify: `src/main/java/com/mrsmith/tool/EditTool.java`
- Modify: `src/main/java/com/mrsmith/tool/WriteFileTool.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mrsmith/tool/AtomicFilesTest.java`:

```java
package com.mrsmith.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AtomicFilesTest {

    @TempDir
    Path tempDir;

    @Test
    void writesNewFile() throws IOException {
        Path target = tempDir.resolve("new.txt");
        AtomicFiles.write(target, "hello".getBytes(StandardCharsets.UTF_8));
        assertEquals("hello", Files.readString(target));
    }

    @Test
    void overwritesExistingFile() throws IOException {
        Path target = tempDir.resolve("file.txt");
        Files.writeString(target, "old");
        AtomicFiles.write(target, "new".getBytes(StandardCharsets.UTF_8));
        assertEquals("new", Files.readString(target));
    }

    @Test
    void preservesExistingFilePermissions() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path target = tempDir.resolve("script.sh");
        Files.writeString(target, "old");
        Files.setPosixFilePermissions(target,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        AtomicFiles.write(target, "new".getBytes(StandardCharsets.UTF_8));
        assertTrue(Files.getPosixFilePermissions(target).contains(PosixFilePermission.OWNER_EXECUTE));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -Dtest=AtomicFilesTest test`
Expected: FAIL — compilation error, `AtomicFiles` not defined.

- [ ] **Step 3: Implement `AtomicFiles`**

Create `src/main/java/com/mrsmith/tool/AtomicFiles.java`:

```java
package com.mrsmith.tool;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class AtomicFiles {

    private AtomicFiles() {
    }

    public static void write(Path target, byte[] content) throws IOException {
        if (Files.exists(target)) {
            replaceAtomically(target, content);
        } else {
            Files.write(target, content);
        }
    }

    private static void replaceAtomically(Path target, byte[] content) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(parent, ".mrsmith-", ".tmp");
        try {
            Files.write(temp, content);
            preservePermissions(target, temp);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private static void preservePermissions(Path target, Path temp) {
        try {
            Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target));
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }
}
```

New files are written with plain `Files.write` (umask-derived permissions, matching the old `Files.writeString` behavior); only existing files go through the temp-file + atomic-move path (that is where a crash could corrupt data).

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -Dtest=AtomicFilesTest test`
Expected: PASS.

- [ ] **Step 5: Update EditTool**

In `src/main/java/com/mrsmith/tool/EditTool.java`, replace:

```java
            Files.writeString(real, updated, StandardCharsets.UTF_8);
```

with:

```java
            AtomicFiles.write(real, updated.getBytes(StandardCharsets.UTF_8));
```

(`AtomicFiles` is in the same package — no import needed.)

- [ ] **Step 6: Update WriteFileTool**

In `src/main/java/com/mrsmith/tool/WriteFileTool.java`, add the import `import java.nio.charset.StandardCharsets;` and replace:

```java
            Files.writeString(target, content);
```

with:

```java
            AtomicFiles.write(target, content.getBytes(StandardCharsets.UTF_8));
```

- [ ] **Step 7: Run the full suite**

Run: `mvn test 2>&1 | grep -E "Tests run: [0-9]+, Failures"`
Expected: BUILD SUCCESS — existing `EditToolTest`, `FileToolsTest`, and the rest pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/mrsmith/tool/AtomicFiles.java src/test/java/com/mrsmith/tool/AtomicFilesTest.java src/main/java/com/mrsmith/tool/EditTool.java src/main/java/com/mrsmith/tool/WriteFileTool.java
git commit -m "feat: write files atomically in edit and write_file tools"
```

---

### Task 5: Unified usage accounting

**Files:**
- Create: `src/main/java/com/mrsmith/chat/UsageAccumulator.java`
- Create: `src/test/java/com/mrsmith/chat/UsageAccumulatorTest.java`
- Modify: `src/main/java/com/mrsmith/chat/ToolLoop.java`
- Modify: `src/main/java/com/mrsmith/chat/UsageTracker.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mrsmith/chat/UsageAccumulatorTest.java`:

```java
package com.mrsmith.chat;

import com.mrsmith.provider.Usage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageAccumulatorTest {

    @Test
    void accumulatesFields() {
        UsageAccumulator acc = new UsageAccumulator();
        acc.add(new Usage(1200, 300), false);
        acc.add(new Usage(800, 200), false);
        assertEquals(2000, acc.promptTokens());
        assertEquals(500, acc.completionTokens());
        assertEquals(2500, acc.totalTokens());
        assertFalse(acc.estimated());
    }

    @Test
    void skipsNullUsageAndFields() {
        UsageAccumulator acc = new UsageAccumulator();
        acc.add(null, false);
        acc.add(new Usage(1200, null), false);
        assertEquals(1200, acc.promptTokens());
        assertEquals(0, acc.completionTokens());
    }

    @Test
    void flagsEstimatedOnce() {
        UsageAccumulator acc = new UsageAccumulator();
        acc.add(new Usage(100, 50), true);
        acc.add(new Usage(100, 50), false);
        assertTrue(acc.estimated());
    }

    @Test
    void snapshotReturnsCurrentTotals() {
        UsageAccumulator acc = new UsageAccumulator();
        acc.add(new Usage(1200, 300), true);
        assertEquals(new Usage(1200, 300), acc.snapshot());
        assertTrue(acc.estimated());
    }

    @Test
    void resetClears() {
        UsageAccumulator acc = new UsageAccumulator();
        acc.add(new Usage(100, 50), true);
        acc.reset();
        assertEquals(0, acc.totalTokens());
        assertFalse(acc.estimated());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -Dtest=UsageAccumulatorTest test`
Expected: FAIL — compilation error, `UsageAccumulator` not defined.

- [ ] **Step 3: Implement `UsageAccumulator`**

Create `src/main/java/com/mrsmith/chat/UsageAccumulator.java`:

```java
package com.mrsmith.chat;

import com.mrsmith.provider.Usage;

public final class UsageAccumulator {

    private int promptTokens;
    private int completionTokens;
    private boolean estimated;

    public void add(Usage usage, boolean estimated) {
        if (usage == null) {
            return;
        }
        if (estimated) {
            this.estimated = true;
        }
        if (usage.promptTokens() != null) {
            promptTokens += usage.promptTokens();
        }
        if (usage.completionTokens() != null) {
            completionTokens += usage.completionTokens();
        }
    }

    public int promptTokens() {
        return promptTokens;
    }

    public int completionTokens() {
        return completionTokens;
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public boolean estimated() {
        return estimated;
    }

    public Usage snapshot() {
        return new Usage(promptTokens, completionTokens);
    }

    public void reset() {
        promptTokens = 0;
        completionTokens = 0;
        estimated = false;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -Dtest=UsageAccumulatorTest test`
Expected: PASS.

- [ ] **Step 5: Update ToolLoop to use it**

In `src/main/java/com/mrsmith/chat/ToolLoop.java`:

1. Replace the `Accumulator acc = new Accumulator();` line with `UsageAccumulator acc = new UsageAccumulator();`.
2. Replace the two `accumulate(acc, response);` / `accumulate(acc, finalResponse);` calls with `acc.add(response.usage(), response.usageEstimated());` / `acc.add(finalResponse.usage(), finalResponse.usageEstimated());`.
3. Replace `new Usage(acc.prompt, acc.completion)` (both in `run` and `finalAnswer`) with `acc.snapshot()`, and `acc.estimated` with `acc.estimated()`.
4. Delete the private static `accumulate(...)` helper, the private static `tokens(...)` helper, and the private `static final class Accumulator { ... }` class.

The `finalAnswer` method signature changes from `(Accumulator acc, ...)` to `(UsageAccumulator acc, ...)`.

- [ ] **Step 6: Update UsageTracker to delegate**

Replace the full content of `src/main/java/com/mrsmith/chat/UsageTracker.java` with:

```java
package com.mrsmith.chat;

import com.mrsmith.provider.Usage;

import java.util.Locale;

public class UsageTracker {

    private final UsageAccumulator accumulator = new UsageAccumulator();
    private Usage lastTurn;
    private boolean lastTurnEstimated;

    public void recordTurn(Usage usage, boolean estimated) {
        if (usage == null) {
            return;
        }
        lastTurn = usage;
        lastTurnEstimated = estimated;
        accumulator.add(usage, estimated);
    }

    public void recordSessionUsage(Usage usage, boolean estimated) {
        if (usage == null) {
            return;
        }
        accumulator.add(usage, estimated);
    }

    public String lastTurnLine() {
        if (lastTurn == null) {
            return "";
        }
        int in = lastTurn.promptTokens() == null ? 0 : lastTurn.promptTokens();
        int out = lastTurn.completionTokens() == null ? 0 : lastTurn.completionTokens();
        String turnEst = lastTurnEstimated ? " (est.)" : "";
        String sessionEst = accumulator.estimated() ? " (est.)" : "";
        return String.format(Locale.US,
                "tokens: %,d in%s · %,d out%s · total %,d · session %,d%s",
                in, turnEst, out, turnEst, in + out, totalTokens(), sessionEst);
    }

    public String usageReport() {
        String est = accumulator.estimated() ? " (est.)" : "";
        return String.format(Locale.US,
                "Session usage:%n  prompt:      %,d%n  completion:  %,d%n  total:       %,d%s",
                promptTokens(), completionTokens(), totalTokens(), est);
    }

    public int promptTokens() {
        return accumulator.promptTokens();
    }

    public int completionTokens() {
        return accumulator.completionTokens();
    }

    public int totalTokens() {
        return accumulator.totalTokens();
    }

    public boolean sessionEstimated() {
        return accumulator.estimated();
    }

    public void reset() {
        accumulator.reset();
        lastTurn = null;
        lastTurnEstimated = false;
    }
}
```

- [ ] **Step 7: Run the full suite**

Run: `mvn test 2>&1 | grep -E "Tests run: [0-9]+, Failures"`
Expected: BUILD SUCCESS — `UsageTrackerTest`, `ChatSessionTest`, `SubAgentRunnerTest` all pass unchanged (299 + 5 new).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/mrsmith/chat/UsageAccumulator.java src/test/java/com/mrsmith/chat/UsageAccumulatorTest.java src/main/java/com/mrsmith/chat/ToolLoop.java src/main/java/com/mrsmith/chat/UsageTracker.java
git commit -m "refactor: share usage accounting between ToolLoop and UsageTracker"
```

---

### Task 6: SubAgentRunner context record + registry-drift fix

`SubAgentRunner` and its test call sites change together. This task also routes sub-agent registry construction through `ToolRegistryFactory`.

**Files:**
- Modify: `src/main/java/com/mrsmith/chat/SubAgentRunner.java`
- Modify: `src/main/java/com/mrsmith/chat/ChatSession.java`
- Modify: `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java`

- [ ] **Step 1: Update SubAgentRunner**

In `src/main/java/com/mrsmith/chat/SubAgentRunner.java`:

1. Add imports:

```java
import com.mrsmith.skill.SkillCatalog;
import com.mrsmith.tool.ToolRegistryFactory;
```

and remove the now-unused `import java.util.function.Function;`.

2. Change the class to take a `Context` record. Replace the field declarations and constructor:

```java
    public record Context(AgentCatalog agents, ProviderFactory providerFactory,
                          ToolRegistryFactory toolRegistryFactory, SkillCatalog skills,
                          IO io, UsageTracker tracker,
                          Supplier<AppConfig> currentConfig,
                          Supplier<UUID> sessionId, Supplier<ToolBudget> budget) {
    }

    private final AgentCatalog agents;
    private final ProviderFactory providerFactory;
    private final ToolRegistryFactory toolRegistryFactory;
    private final SkillCatalog skills;
    private final IO io;
    private final UsageTracker tracker;
    private final Supplier<AppConfig> currentConfig;
    private final Supplier<UUID> sessionId;
    private final Supplier<ToolBudget> budget;
    private final SubAgentTranscriptStore store;

    private int counter;

    public SubAgentRunner(Context context) {
        this.agents = context.agents();
        this.providerFactory = context.providerFactory();
        this.toolRegistryFactory = context.toolRegistryFactory();
        this.skills = context.skills();
        this.io = context.io();
        this.tracker = context.tracker();
        this.currentConfig = context.currentConfig();
        this.sessionId = context.sessionId();
        this.budget = context.budget();
        this.store = new SubAgentTranscriptStore(agents.sessionsDir(), sessionId);
    }
```

3. In `run(...)`, replace:

```java
        ToolRegistry tools = toolsBuilder.apply(config);
```

with:

```java
        ToolRegistry tools = toolRegistryFactory.create(config, skills, io, null);
```

- [ ] **Step 2: Update ChatSession**

In `src/main/java/com/mrsmith/chat/ChatSession.java`, replace the `applyAgent` body's `SubAgentRunner` construction:

```java
        subAgentRunner = new SubAgentRunner(agents, providerFactory,
                cfg -> ToolRegistry.with(cfg.tools(), skills, io, null),
                io, tracker, () -> config, () -> currentSessionId, () -> toolBudget);
```

with:

```java
        subAgentRunner = new SubAgentRunner(new SubAgentRunner.Context(
                agents, providerFactory, toolRegistryFactory, skills, io, tracker,
                () -> config, () -> currentSessionId, () -> toolBudget));
```

- [ ] **Step 3: Update SubAgentRunnerTest**

In `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java`:

1. Add a helper for an empty skill catalog and update the `runner(...)` helper. Replace:

```java
    private SubAgentRunner runner(Provider provider, ToolRegistry tools, IO io) throws IOException {
        Files.createDirectories(tempDir.resolve(sessionId.toString()));
        AgentCatalog catalog = catalog();
        ProviderFactory factory = new FakeProviderFactory(provider);
        UsageTracker tracker = new UsageTracker();
        return new SubAgentRunner(catalog, factory, cfg -> tools, io, tracker,
                () -> catalog.resolve("a"), () -> sessionId, () -> new ToolBudget(null, io));
    }
```

with:

```java
    private SkillCatalog emptySkills() {
        return SkillCatalog.discover(tempDir.resolve("nope-project"), tempDir.resolve("nope-global"));
    }

    private ToolRegistryFactory fixedRegistry(ToolRegistry tools) {
        return (config, catalog, io, taskRunner) -> tools;
    }

    private SubAgentRunner runner(Provider provider, ToolRegistry tools, IO io) throws IOException {
        Files.createDirectories(tempDir.resolve(sessionId.toString()));
        AgentCatalog catalog = catalog();
        ProviderFactory factory = new FakeProviderFactory(provider);
        UsageTracker tracker = new UsageTracker();
        return new SubAgentRunner(new SubAgentRunner.Context(catalog, factory, fixedRegistry(tools),
                emptySkills(), io, tracker, () -> catalog.resolve("a"), () -> sessionId,
                () -> new ToolBudget(null, io)));
    }
```

Add the imports `import com.mrsmith.skill.SkillCatalog;` and `import com.mrsmith.tool.ToolRegistryFactory;`.

2. Update the four inline `new SubAgentRunner(...)` call sites to the `Context` form:

- `subAgentUsageAccumulatesInSessionTracker` (lines ~307-309):
```java
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRunner.Context(catalog,
                new FakeProviderFactory(new FakeProvider()), fixedRegistry(new ToolRegistry(List.of())),
                emptySkills(), new StubIo(List.of()), tracker,
                () -> catalog.resolve("a"), () -> sessionId,
                () -> new ToolBudget(null, new StubIo(List.of()))));
```
- `runsWithoutTranscriptWhenSessionIdIsNull` (lines ~319-321):
```java
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRunner.Context(catalog,
                new FakeProviderFactory(new FakeProvider()), fixedRegistry(new ToolRegistry(List.of())),
                emptySkills(), new StubIo(List.of()), new UsageTracker(),
                () -> catalog.resolve("a"), () -> null,
                () -> new ToolBudget(null, new StubIo(List.of()))));
```
- `subAgentCallsCountAgainstSharedBudget` (lines ~336-338):
```java
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRunner.Context(catalog,
                new FakeProviderFactory(provider), fixedRegistry(tools),
                emptySkills(), io, new UsageTracker(), () -> catalog.resolve("a"), () -> sessionId,
                () -> budget));
```
- `subAgentContinuesToolRoundsWhenUserExtends` (lines ~363-365):
```java
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRunner.Context(catalog,
                new FakeProviderFactory(provider), fixedRegistry(tools),
                emptySkills(), io, new UsageTracker(), () -> catalog.resolve("a"), () -> sessionId,
                () -> new ToolBudget(null, io)));
```

- [ ] **Step 4: Run the full suite**

Run: `mvn test 2>&1 | grep -E "Tests run: [0-9]+, Failures"`
Expected: BUILD SUCCESS — `ChatSessionTest`, `SubAgentRunnerTest`, `ToolRegistryTest` all pass (299 + prior additions).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/chat/SubAgentRunner.java src/main/java/com/mrsmith/chat/ChatSession.java src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java
git commit -m "refactor: route sub-agent registry construction through the factory with a SubAgentRunner context"
```

---

### Task 7: README architecture table

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add the `util` row**

In `README.md`, in the "Architecture" section's package table, add a row after the `skill` row:

```markdown
| `util` | Shared `Json` ObjectMapper and `Warn` warning output |
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: add util package to README architecture table"
```
