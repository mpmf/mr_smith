# Shell Command Classification & Per-Command Approval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Classify shell commands as SAFE/DANGEROUS/UNKNOWN so read-only commands run without prompting while filesystem-modifying or unknown commands require per-command approval.

**Architecture:** A pure `ShellCommandClassifier` parses the command string and returns a verdict plus always-allow keys. The `Tool` interface gains a default `approvalCheck(args)` method that subsumes the `isReadOnly()` gate in `ToolLoop`; `ShellTool` overrides it to delegate to the classifier. Always-allow records the canonical command identity (`git commit`, `rm`) instead of the whole `shell` tool. Configurable per-agent via `shellHarmlessCommands`/`shellDangerousCommands`.

**Tech Stack:** Java 21 (Maven, JUnit 5, Jackson). Build: `mvn -q package`. Test: `mvn test`.

**Spec:** `docs/superpowers/specs/2026-08-10-shell-command-classification-design.md`

---

## Task 1: `ShellConfig` + `ShellCommandClassifier`

**Files:**
- Create: `src/main/java/com/mrsmith/config/ShellConfig.java`
- Create: `src/main/java/com/mrsmith/tool/ShellCommandClassifier.java`
- Create: `src/test/java/com/mrsmith/tool/ShellCommandClassifierTest.java`

- [ ] **Step 1: Write the failing classifier tests**

Create `src/test/java/com/mrsmith/tool/ShellCommandClassifierTest.java`:

```java
package com.mrsmith.tool;

import com.mrsmith.config.ShellConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellCommandClassifierTest {

    private final ShellCommandClassifier classifier = new ShellCommandClassifier();

    @Test
    void safeCommandRequiresNoApproval() {
        assertFalse(classifier.classify("ls -la").requiresApproval());
    }

    @Test
    void safeBuiltinsRequireNoApproval() {
        assertFalse(classifier.classify("cd && pwd && echo hi").requiresApproval());
    }

    @Test
    void dangerousCommandRequiresApprovalWithBinaryKey() {
        ShellCommandClassifier.Classification c = classifier.classify("rm -rf target");
        assertTrue(c.requiresApproval());
        assertEquals(List.of("rm"), c.keys());
    }

    @Test
    void unknownCommandRequiresApproval() {
        ShellCommandClassifier.Classification c = classifier.classify("frobnicate x");
        assertTrue(c.requiresApproval());
        assertEquals(List.of("frobnicate"), c.keys());
    }

    @Test
    void chainWithDangerousPartRequiresApproval() {
        ShellCommandClassifier.Classification c = classifier.classify("ls && rm -rf target");
        assertTrue(c.requiresApproval());
        assertEquals(List.of("rm"), c.keys());
    }

    @Test
    void chainWithUnknownPartRequiresApproval() {
        ShellCommandClassifier.Classification c = classifier.classify("ls && frobnicate x");
        assertTrue(c.requiresApproval());
        assertEquals(ShellCommandClassifier.Verdict.UNKNOWN, c.verdict());
        assertEquals(List.of("frobnicate"), c.keys());
    }

    @Test
    void redirectionMarksDangerousWithWholeCommandKey() {
        ShellCommandClassifier.Classification c = classifier.classify("cat f > out");
        assertTrue(c.requiresApproval());
        assertEquals(List.of("cat >"), c.keys());
    }

    @Test
    void quotedGreaterThanIsNotRedirection() {
        assertFalse(classifier.classify("echo \">\"").requiresApproval());
    }

    @Test
    void gitStatusIsSafe() {
        assertFalse(classifier.classify("git status").requiresApproval());
    }

    @Test
    void gitCommitIsDangerousWithSubcommandKey() {
        ShellCommandClassifier.Classification c = classifier.classify("git commit -m x");
        assertTrue(c.requiresApproval());
        assertEquals(List.of("git commit"), c.keys());
    }

    @Test
    void gitUnknownSubcommandIsDangerous() {
        ShellCommandClassifier.Classification c = classifier.classify("git nope");
        assertTrue(c.requiresApproval());
        assertEquals(List.of("git nope"), c.keys());
    }

    @Test
    void bareGitIsDangerous() {
        assertTrue(classifier.classify("git").requiresApproval());
    }

    @Test
    void chainKeysCoverEachDangerousSegment() {
        ShellCommandClassifier.Classification c = classifier.classify("git add x && git commit -m y");
        assertTrue(c.requiresApproval());
        assertEquals(List.of("git add", "git commit"), c.keys());
    }

    @Test
    void blankCommandIsSafe() {
        assertFalse(classifier.classify("   ").requiresApproval());
        assertFalse(classifier.classify(null).requiresApproval());
    }

    @Test
    void configPromotesSubcommandToSafe() {
        ShellCommandClassifier configurable = new ShellCommandClassifier(
                new ShellConfig(List.of("kubectl get"), List.of()));
        assertFalse(configurable.classify("kubectl get pods").requiresApproval());
        ShellCommandClassifier.Classification c = configurable.classify("kubectl apply -f x");
        assertTrue(c.requiresApproval());
        assertEquals(List.of("kubectl apply"), c.keys());
    }

    @Test
    void configWholeBinaryPromotesToSafe() {
        ShellCommandClassifier configurable = new ShellCommandClassifier(
                new ShellConfig(List.of("ps"), List.of()));
        assertFalse(configurable.classify("ps aux").requiresApproval());
    }

    @Test
    void configDangerousOverridesBuiltinSafe() {
        ShellCommandClassifier configurable = new ShellCommandClassifier(
                new ShellConfig(List.of(), List.of("echo")));
        assertTrue(configurable.classify("echo hi").requiresApproval());
    }

    @Test
    void configDangerousSubcommandOverridesHarmless() {
        ShellCommandClassifier configurable = new ShellCommandClassifier(
                new ShellConfig(List.of("kubectl get"), List.of("kubectl get")));
        assertTrue(configurable.classify("kubectl get pods").requiresApproval());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=ShellCommandClassifierTest`
Expected: FAIL with compilation errors — `ShellConfig` and `ShellCommandClassifier` do not exist.

- [ ] **Step 3: Create `ShellConfig`**

Create `src/main/java/com/mrsmith/config/ShellConfig.java`:

```java
package com.mrsmith.config;

import java.util.List;

/** Config extension points for shell command classification (empty = use built-in defaults). */
public record ShellConfig(List<String> harmlessCommands, List<String> dangerousCommands) {

    public static ShellConfig empty() {
        return new ShellConfig(List.of(), List.of());
    }
}
```

- [ ] **Step 4: Implement `ShellCommandClassifier`**

Create `src/main/java/com/mrsmith/tool/ShellCommandClassifier.java`:

```java
package com.mrsmith.tool;

import com.mrsmith.config.ShellConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Heuristic shell command classifier. Not a security boundary: it only decides
 * whether the shell approval prompt is shown. DANGEROUS and UNKNOWN both
 * require approval; the distinction is used for the prompt label.
 */
public final class ShellCommandClassifier {

    enum Verdict { SAFE, DANGEROUS, UNKNOWN }

    record Classification(Verdict verdict, List<String> keys) {

        static Classification safe() {
            return new Classification(Verdict.SAFE, List.of());
        }

        boolean requiresApproval() {
            return verdict != Verdict.SAFE;
        }
    }

    private static final Set<String> SAFE = Set.of(
            "ls", "cat", "pwd", "echo", "printf", "head", "tail", "wc", "grep",
            "find", "diff", "sort", "uniq", "cut", "tr", "file", "stat", "du",
            "df", "which", "readlink", "basename", "dirname", "date", "cal",
            "whoami", "uname", "hostname", "env", "printenv", "id", "tree",
            "cd", "export", "set", "unset");

    private static final Set<String> DANGEROUS = Set.of(
            "rm", "rmdir", "mv", "cp", "touch", "mkdir", "chmod", "chown", "ln",
            "dd", "tee", "sed", "truncate", "install", "patch", "shred",
            "unlink", "mount", "umount");

    private static final Map<String, Set<String>> SAFE_SUBCOMMANDS = Map.of(
            "git", Set.of("status", "diff", "log", "show", "branch", "ls-files",
                    "rev-parse", "remote", "tag"));

    private final Set<String> safeBinaries;
    private final Set<String> dangerousBinaries;
    private final Map<String, Set<String>> safeSubcommands;
    private final Map<String, Set<String>> dangerousSubcommands;

    public ShellCommandClassifier() {
        this(new ShellConfig(List.of(), List.of()));
    }

    public ShellCommandClassifier(ShellConfig config) {
        this.safeBinaries = new HashSet<>(SAFE);
        this.dangerousBinaries = new HashSet<>(DANGEROUS);
        this.safeSubcommands = new HashMap<>();
        SAFE_SUBCOMMANDS.forEach((binary, subs) ->
                safeSubcommands.put(binary, new HashSet<>(subs)));
        this.dangerousSubcommands = new HashMap<>();

        for (String spec : config.harmlessCommands()) {
            String[] parts = spec.trim().split("\\s+");
            if (parts.length <= 1) {
                safeBinaries.add(normalize(parts[0]));
            } else {
                safeSubcommands.computeIfAbsent(normalize(parts[0]), k -> new HashSet<>())
                        .add(normalize(parts[1]));
            }
        }
        for (String spec : config.dangerousCommands()) {
            String[] parts = spec.trim().split("\\s+");
            if (parts.length <= 1) {
                dangerousBinaries.add(normalize(parts[0]));
            } else {
                dangerousSubcommands.computeIfAbsent(normalize(parts[0]), k -> new HashSet<>())
                        .add(normalize(parts[1]));
            }
        }
    }

    public Classification classify(String command) {
        if (command == null || command.isBlank()) {
            return Classification.safe();
        }
        Parsed parsed = parse(command);
        Verdict verdict = Verdict.SAFE;
        List<String> keys = new ArrayList<>();
        StringBuilder redirectKey = new StringBuilder();
        for (Segment segment : parsed.segments) {
            String trimmed = segment.text.trim();
            if (trimmed.isEmpty()) {
                redirectKey.append(segment.separator);
                continue;
            }
            String[] words = trimmed.split("\\s+");
            String binary = normalize(words[0]);
            String subcommand = words.length > 1 ? normalize(words[1]) : null;
            Verdict v = classifySegment(binary, subcommand);
            if (v == Verdict.DANGEROUS) {
                verdict = Verdict.DANGEROUS;
            } else if (v == Verdict.UNKNOWN && verdict == Verdict.SAFE) {
                verdict = Verdict.UNKNOWN;
            }
            boolean aware = subcommandAware(binary);
            redirectKey.append(canonical(binary, subcommand, aware, segment.redirect));
            redirectKey.append(segment.separator);
            if (v != Verdict.SAFE) {
                keys.add(canonical(binary, subcommand, aware, false));
            }
        }
        if (parsed.redirection) {
            return new Classification(Verdict.DANGEROUS, List.of(redirectKey.toString()));
        }
        return new Classification(verdict, List.copyOf(keys));
    }

    private Verdict classifySegment(String binary, String subcommand) {
        if (dangerousBinaries.contains(binary)) {
            return Verdict.DANGEROUS;
        }
        Set<String> dangerSubs = dangerousSubcommands.get(binary);
        Set<String> safeSubs = safeSubcommands.get(binary);
        if (subcommand != null) {
            if (dangerSubs != null && dangerSubs.contains(subcommand)) {
                return Verdict.DANGEROUS;
            }
            if (safeSubs != null) {
                return safeSubs.contains(subcommand) ? Verdict.SAFE : Verdict.DANGEROUS;
            }
        }
        if (safeBinaries.contains(binary)) {
            return Verdict.SAFE;
        }
        if (dangerSubs != null || safeSubs != null) {
            return Verdict.DANGEROUS;
        }
        return Verdict.UNKNOWN;
    }

    private boolean subcommandAware(String binary) {
        return safeSubcommands.containsKey(binary) || dangerousSubcommands.containsKey(binary);
    }

    private String canonical(String binary, String subcommand, boolean aware, boolean redirect) {
        StringBuilder sb = new StringBuilder(binary);
        if (aware && subcommand != null) {
            sb.append(' ').append(subcommand);
        }
        if (redirect) {
            sb.append(" >");
        }
        return sb.toString();
    }

    private static String normalize(String word) {
        return word == null ? "" : word.toLowerCase(Locale.ROOT);
    }

    private record Segment(String text, String separator, boolean redirect) {
    }

    private record Parsed(List<Segment> segments, boolean redirection) {
    }

    private static Parsed parse(String command) {
        List<Segment> segments = new ArrayList<>();
        boolean redirection = false;
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                current.append(c);
                escaped = true;
                continue;
            }
            if (quote != 0) {
                current.append(c);
                if (quote == '\'' && c == '\'') {
                    quote = 0;
                } else if (quote == '"' && c == '"') {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == '>') {
                redirection = true;
                current.append(c);
                continue;
            }
            String sep = null;
            if (c == '&' && i + 1 < command.length() && command.charAt(i + 1) == '&') {
                sep = "&&";
                i++;
            } else if (c == '|' && i + 1 < command.length() && command.charAt(i + 1) == '|') {
                sep = "||";
                i++;
            } else if (c == '&') {
                sep = "&";
            } else if (c == '|') {
                sep = "|";
            } else if (c == ';' || c == '\n') {
                sep = ";";
            }
            if (sep != null) {
                segments.add(new Segment(current.toString(), sep, redirectionIn(current)));
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        segments.add(new Segment(current.toString(), "", redirectionIn(current)));
        return new Parsed(segments, redirection);
    }

    private static boolean redirectionIn(StringBuilder current) {
        return current.indexOf(">") >= 0;
    }
}
```

The `Segment.separator` field is used to build the redirect always-allow key (`cat f > out` → `cat >`).

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=ShellCommandClassifierTest`
Expected: PASS (all 18 tests).

- [ ] **Step 6: Run the full suite and commit**

Run: `mvn -q test`
Expected: all existing tests still pass.

```bash
git add src/main/java/com/mrsmith/config/ShellConfig.java \
        src/main/java/com/mrsmith/tool/ShellCommandClassifier.java \
        src/test/java/com/mrsmith/tool/ShellCommandClassifierTest.java
git commit -m "feat: add shell command classifier with per-command verdicts and keys"
```

---

## Task 2: `Tool.ApprovalCheck` + `ToolLoop` per-command approval

**Files:**
- Modify: `src/main/java/com/mrsmith/tool/Tool.java`
- Modify: `src/main/java/com/mrsmith/chat/ToolLoop.java:111-133` (`executeTool`), `144-163` (`confirm`)
- Modify: `src/test/java/com/mrsmith/chat/ChatSessionTest.java` (add `MultiKeyTool` + new test)

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/mrsmith/chat/ChatSessionTest.java` (near the other always-allow tests, e.g. after `alwaysAllowsToolAcrossTurnsWithoutReprompting`):

```java
    @Test
    void alwaysAllowRecordsAllApprovalKeys() throws Exception {
        MultiKeyTool tool = new MultiKeyTool();
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> new ToolRegistry(List.of(tool));
        FakeToolProvider toolProvider = new FakeToolProvider();
        toolProvider.alwaysCall("multi", JSON.readTree("{}"));
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "a", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        assertEquals(9, tool.calls);
        long prompts = io.lines.stream().filter(l -> l.startsWith("Run multi(")).count();
        assertEquals(1, prompts);
    }
```

And add the helper tool class next to `FakeTool` (after its closing brace):

```java
    static class MultiKeyTool extends FakeTool {
        MultiKeyTool() {
            super("multi", false, new ToolResult("ran", false));
        }

        @Override
        public Tool.ApprovalCheck approvalCheck(JsonNode args) {
            return new Tool.ApprovalCheck(List.of("k1", "k2"), "dangerous command");
        }
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=ChatSessionTest#alwaysAllowRecordsAllApprovalKeys`
Expected: FAIL — `Tool.ApprovalCheck` does not exist (compilation error).

- [ ] **Step 3: Add `approvalCheck` to the `Tool` interface**

Modify `src/main/java/com/mrsmith/tool/Tool.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public interface Tool {

    String name();

    String description();

    JsonNode parametersSchema();

    boolean isReadOnly();

    default ApprovalCheck approvalCheck(JsonNode args) {
        return isReadOnly() ? null : new ApprovalCheck(List.of(name()), null);
    }

    ToolResult execute(JsonNode args);

    record ApprovalCheck(List<String> keys, String reason) {
    }
}
```

- [ ] **Step 4: Update `ToolLoop` to use `approvalCheck`**

Modify `src/main/java/com/mrsmith/chat/ToolLoop.java`.

Replace `executeTool` (currently lines 111-133) with:

```java
    private static ToolResult executeTool(ToolCall call, List<Tool> tools, IO io, ToolApproval approval) {
        Optional<Tool> found = find(tools, call.name());
        if (found.isEmpty()) {
            return new ToolResult("Unknown tool: " + call.name(), true);
        }
        Tool tool = found.get();
        Tool.ApprovalCheck check = tool.approvalCheck(call.arguments());
        if (check != null) {
            boolean allAllowed = check.keys().stream().allMatch(approval::isAlwaysAllowed);
            if (!allAllowed) {
                ConfirmDecision decision = confirm(call, tool, check, io);
                if (decision == ConfirmDecision.DECLINE) {
                    return new ToolResult("User declined to run " + call.name() + ".", true);
                }
                if (decision == ConfirmDecision.ALWAYS_ALLOW) {
                    check.keys().forEach(approval::allowAlways);
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

Replace `confirm` (currently lines 144-163) with:

```java
    private static ConfirmDecision confirm(ToolCall call, Tool tool, Tool.ApprovalCheck check, IO io) {
        String suffix = check.reason() != null ? " (" + check.reason() + ")" : "";
        io.writePrompt("Run " + tool.name() + "(" + describe(call) + ")" + suffix + " [y/N/a=always]? ");
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

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q test -Dtest=ChatSessionTest#alwaysAllowRecordsAllApprovalKeys`
Expected: PASS.

- [ ] **Step 6: Run the full suite and commit**

Run: `mvn -q test`
Expected: all existing tests still pass (FakeTool-based approval tests behave identically — read-only tools return null, non-read-only tools return `[name()]`).

```bash
git add src/main/java/com/mrsmith/tool/Tool.java \
        src/main/java/com/mrsmith/chat/ToolLoop.java \
        src/test/java/com/mrsmith/chat/ChatSessionTest.java
git commit -m "feat: drive tool approval from per-call approvalCheck keys"
```

---

## Task 3: `ShellTool` approvalCheck override + integration tests

**Files:**
- Modify: `src/main/java/com/mrsmith/tool/ShellTool.java`
- Modify: `src/test/java/com/mrsmith/tool/ShellToolTest.java`
- Modify: `src/test/java/com/mrsmith/chat/ChatSessionTest.java` (3 integration tests)
- Modify: `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java` (1 test)

- [ ] **Step 1: Write the failing ShellTool tests**

Add to `src/test/java/com/mrsmith/tool/ShellToolTest.java`. Update the imports to:

```java
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
```

Add these tests:

```java
    @Test
    void safeCommandNeedsNoApproval() throws Exception {
        ShellTool tool = new ShellTool(tempDir, 5000);
        assertNull(tool.approvalCheck(JSON.readTree("{\"command\":\"ls marker.txt\"}")));
    }

    @Test
    void dangerousCommandNeedsApprovalWithKeyAndReason() throws Exception {
        ShellTool tool = new ShellTool(tempDir, 5000);
        Tool.ApprovalCheck check = tool.approvalCheck(JSON.readTree("{\"command\":\"rm marker.txt\"}"));
        assertNotNull(check);
        assertEquals(List.of("rm"), check.keys());
        assertEquals("dangerous command", check.reason());
    }

    @Test
    void unknownCommandNeedsApprovalWithUnknownReason() throws Exception {
        ShellTool tool = new ShellTool(tempDir, 5000);
        Tool.ApprovalCheck check = tool.approvalCheck(JSON.readTree("{\"command\":\"frobnicate x\"}"));
        assertNotNull(check);
        assertEquals("unknown command", check.reason());
    }

    @Test
    void blankCommandNeedsNoApproval() throws Exception {
        ShellTool tool = new ShellTool(tempDir, 5000);
        assertNull(tool.approvalCheck(JSON.readTree("{\"command\":\"\"}")));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=ShellToolTest`
Expected: FAIL — `ShellTool.approvalCheck` does not exist yet (inherits the default, so `rm marker.txt` returns `["shell"]` instead of `null`/`["rm"]`; the assertions fail).

- [ ] **Step 3: Implement the `ShellTool` override**

Modify `src/main/java/com/mrsmith/tool/ShellTool.java`:

Add the field and constructors (replace the existing constructors, lines 19-29):

```java
    private final Path workDir;
    private final long timeoutMillis;
    private final ShellCommandClassifier classifier;

    public ShellTool() {
        this(Path.of("").toAbsolutePath(), 30_000L, new ShellCommandClassifier());
    }

    public ShellTool(Path workDir, long timeoutMillis) {
        this(workDir, timeoutMillis, new ShellCommandClassifier());
    }

    public ShellTool(Path workDir, long timeoutMillis, ShellCommandClassifier classifier) {
        this.workDir = workDir;
        this.timeoutMillis = timeoutMillis;
        this.classifier = classifier;
    }

    public ShellTool(ShellCommandClassifier classifier) {
        this(Path.of("").toAbsolutePath(), 30_000L, classifier);
    }
```

Update `description()`:

```java
    @Override
    public String description() {
        return "Run a shell command via bash -c in the working directory and return its stdout, stderr, and exit code. "
                + "Read-only commands (ls, cat, git status, ...) run automatically; commands that modify the "
                + "filesystem or unknown commands require approval.";
    }
```

Add the `approvalCheck` override (after `isReadOnly()`):

```java
    @Override
    public Tool.ApprovalCheck approvalCheck(JsonNode args) {
        String command = args.path("command").asText(null);
        if (command == null || command.isBlank()) {
            return null;
        }
        ShellCommandClassifier.Classification c = classifier.classify(command);
        if (!c.requiresApproval()) {
            return null;
        }
        String reason = c.verdict() == ShellCommandClassifier.Verdict.DANGEROUS
                ? "dangerous command" : "unknown command";
        return new Tool.ApprovalCheck(c.keys(), reason);
    }
```

- [ ] **Step 4: Run ShellToolTest to verify it passes**

Run: `mvn -q test -Dtest=ShellToolTest`
Expected: PASS.

- [ ] **Step 5: Add ChatSession integration tests**

Add to `src/test/java/com/mrsmith/chat/ChatSessionTest.java`. Add the import `import com.mrsmith.tool.ShellTool;`.

Add these three tests (place near the other shell approval tests):

```java
    @Test
    void safeShellCommandRunsWithoutPrompting() throws Exception {
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) ->
                new ToolRegistry(List.of(new ShellTool(tempDir, 5000)));
        FakeToolProvider toolProvider = new FakeToolProvider();
        toolProvider.alwaysCall("shell", JSON.readTree("{\"command\":\"ls\"}"));
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        long prompts = io.lines.stream().filter(l -> l.startsWith("Run shell(")).count();
        assertEquals(0, prompts);
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("tool: shell(ls) -> ok")));
    }

    @Test
    void dangerousShellCommandPromptsAndAlwaysAllowKeyedOnCommand() throws Exception {
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) ->
                new ToolRegistry(List.of(new ShellTool(tempDir, 5000)));
        FakeToolProvider toolProvider = new FakeToolProvider();
        toolProvider.alwaysCall("shell", JSON.readTree("{\"command\":\"touch marker.txt\"}"));
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "a", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        assertTrue(Files.exists(tempDir.resolve("marker.txt")));
        List<String> prompts = io.lines.stream().filter(l -> l.startsWith("Run shell(")).toList();
        assertEquals(1, prompts.size());
        assertTrue(prompts.get(0).contains("(dangerous command)"));
        assertTrue(prompts.get(0).contains("[y/N/a=always]"));
    }

    @Test
    void dangerousShellCommandDeclinedDoesNotExecute() throws Exception {
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) ->
                new ToolRegistry(List.of(new ShellTool(tempDir, 5000)));
        FakeToolProvider toolProvider = new FakeToolProvider();
        toolProvider.alwaysCall("shell", JSON.readTree("{\"command\":\"touch declined.txt\"}"));
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "n", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        assertFalse(Files.exists(tempDir.resolve("declined.txt")));
        assertTrue(toolProvider.receivedHistories.get(1).stream()
                .anyMatch(m -> m.content() != null && m.content().contains("declined")));
    }
```

- [ ] **Step 6: Add the SubAgentRunner test**

Add to `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java` (before the closing brace, after `subAgentSharesAlwaysAllowDecision`). Add imports `import com.mrsmith.tool.ShellTool;`:

```java
    @Test
    void subAgentSkipsPromptForAlwaysAllowedCommandKey() throws Exception {
        ToolRegistry tools = new ToolRegistry(List.of(new ShellTool(tempDir, 5000)));
        FakeProvider provider = new FakeProvider(
                new ToolCall("c1", "shell", JSON.readTree("{\"command\":\"touch subagent-file.txt\"}")));
        ToolApproval approval = new ToolApproval();
        approval.allowAlways("touch");
        StubIo io = new StubIo(List.of());
        Files.createDirectories(tempDir.resolve(sessionId.toString()));
        AgentCatalog catalog = catalog();
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRunner.Context(catalog,
                new FakeProviderFactory(provider), fixedRegistry(tools), emptySkills(), io,
                new UsageTracker(), () -> catalog.resolve("a"), () -> sessionId,
                () -> new ToolBudget(null, io), () -> approval));
        TaskResult result = runner.run("do it", null, null);
        assertFalse(result.error());
        assertTrue(Files.exists(tempDir.resolve("subagent-file.txt")));
        assertTrue(io.lines.stream().noneMatch(l -> l.contains("Run shell(")));
    }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=ChatSessionTest,ShellToolTest,SubAgentRunnerTest`
Expected: PASS.

- [ ] **Step 8: Run the full suite and commit**

Run: `mvn -q test`
Expected: all tests pass.

```bash
git add src/main/java/com/mrsmith/tool/ShellTool.java \
        src/test/java/com/mrsmith/tool/ShellToolTest.java \
        src/test/java/com/mrsmith/chat/ChatSessionTest.java \
        src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java
git commit -m "feat: classify shell commands for per-command approval"
```

---

## Task 4: Config plumbing (`AgentConfig`, `ConfigLoader`, `ToolRegistry`, `ChatCommand`)

**Files:**
- Modify: `src/main/java/com/mrsmith/config/AgentConfig.java`
- Modify: `src/main/java/com/mrsmith/config/ConfigLoader.java:95-123`
- Modify: `src/main/java/com/mrsmith/tool/ToolRegistry.java:16-58`
- Modify: `src/main/java/com/mrsmith/cli/ChatCommand.java:57`
- Modify: `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`
- Modify: `src/test/java/com/mrsmith/tool/ToolRegistryTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`:

```java
    @Test
    void loadsShellCommandConfigLists() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [
                    {
                      "name": "a", "provider": "p", "model": "m",
                      "shellHarmlessCommands": ["kubectl get", "ps"],
                      "shellDangerousCommands": ["mydeploy --push"]
                    }
                  ],
                  "defaultAgent": "a"
                }
                """);
        AgentRuntime runtime = ConfigLoader.load(file, CliConfig.empty(), Map.of()).resolve("a");
        assertEquals(List.of("kubectl get", "ps"), runtime.agent().shellHarmlessCommands());
        assertEquals(List.of("mydeploy --push"), runtime.agent().shellDangerousCommands());
    }

    @Test
    void shellCommandConfigListsDefaultToEmpty() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentRuntime runtime = ConfigLoader.load(file, CliConfig.empty(), Map.of()).resolve("a");
        assertTrue(runtime.agent().shellHarmlessCommands().isEmpty());
        assertTrue(runtime.agent().shellDangerousCommands().isEmpty());
    }
```

Add to `src/test/java/com/mrsmith/tool/ToolRegistryTest.java`. Update imports to add `import com.mrsmith.config.ShellConfig;` and static imports `assertNotNull`, `assertNull`:

```java
    @Test
    void shellToolUsesProvidedShellConfig() throws Exception {
        ToolRegistry registry = ToolRegistry.with(List.of("shell"), emptyCatalog(), io, taskRunner,
                new ShellConfig(List.of("frobnicate"), List.of()));
        ShellTool shell = (ShellTool) registry.find("shell").orElseThrow();
        assertNull(shell.approvalCheck(JSON.readTree("{\"command\":\"frobnicate x\"}")));
        assertNotNull(shell.approvalCheck(JSON.readTree("{\"command\":\"rm x\"}")));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=ConfigLoaderTest,ToolRegistryTest`
Expected: FAIL — `AgentConfig.shellHarmlessCommands()` and the 5-arg `ToolRegistry.with` overload do not exist.

- [ ] **Step 3: Add fields to `AgentConfig`**

Modify `src/main/java/com/mrsmith/config/AgentConfig.java`:

```java
package com.mrsmith.config;

import java.util.List;

public record AgentConfig(String name, String provider, String model,
                          String systemPrompt, Integer maxContextTokens,
                          Integer maxToolRounds, Integer maxToolCallsPerSession,
                          List<String> tools,
                          List<String> shellHarmlessCommands,
                          List<String> shellDangerousCommands) {

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens) {
        this(name, provider, model, systemPrompt, maxContextTokens, null, null, List.of(), List.of(), List.of());
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, List<String> tools) {
        this(name, provider, model, systemPrompt, maxContextTokens, null, null, tools, List.of(), List.of());
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, Integer maxToolRounds) {
        this(name, provider, model, systemPrompt, maxContextTokens, maxToolRounds, null, List.of(), List.of(), List.of());
    }

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens, Integer maxToolRounds,
                       Integer maxToolCallsPerSession) {
        this(name, provider, model, systemPrompt, maxContextTokens, maxToolRounds, maxToolCallsPerSession, List.of(), List.of(), List.of());
    }
}
```

- [ ] **Step 4: Parse the new fields in `ConfigLoader`**

Modify `src/main/java/com/mrsmith/config/ConfigLoader.java`. Update the `parseAgents` loop body (lines 100-109) to:

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
                        parseStringList(node, "shellDangerousCommands")));
```

Add the helper next to `parseTools`:

```java
    private static List<String> parseStringList(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        JsonNode arr = node.path(field);
        if (arr.isArray()) {
            for (JsonNode item : arr) {
                result.add(item.asText());
            }
        }
        return result;
    }
```

- [ ] **Step 5: Add the `ToolRegistry.with` overload**

Modify `src/main/java/com/mrsmith/tool/ToolRegistry.java`. Add the import `import com.mrsmith.config.ShellConfig;`.

Replace the `with` method (lines 39-58) with:

```java
    public static ToolRegistry with(List<String> toolNames, SkillCatalog catalog, IO io, TaskRunner taskRunner) {
        return with(toolNames, catalog, io, taskRunner, ShellConfig.empty());
    }

    public static ToolRegistry with(List<String> toolNames, SkillCatalog catalog, IO io, TaskRunner taskRunner,
                                    ShellConfig shellConfig) {
        ShellCommandClassifier classifier = new ShellCommandClassifier(shellConfig);
        List<Tool> tools = new ArrayList<>();
        for (String name : toolNames) {
            if (name.equals("shell")) {
                tools.add(new ShellTool(classifier));
                continue;
            }
            Function<IO, Tool> factory = BUILT_INS.get(name);
            if (factory == null) {
                throw new ToolException("Unknown tool: " + name);
            }
            tools.add(factory.apply(io));
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

- [ ] **Step 6: Wire `ChatCommand`**

Modify `src/main/java/com/mrsmith/cli/ChatCommand.java`. Add the import `import com.mrsmith.config.ShellConfig;` and change the registry factory (line 57) to:

```java
                (runtime, skillCatalog, terminalIo, taskRunner) -> ToolRegistry.with(
                        runtime.agent().tools(), skillCatalog, terminalIo, taskRunner,
                        new ShellConfig(runtime.agent().shellHarmlessCommands(),
                                runtime.agent().shellDangerousCommands())),
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=ConfigLoaderTest,ToolRegistryTest`
Expected: PASS.

- [ ] **Step 8: Run the full suite and commit**

Run: `mvn -q test`
Expected: all tests pass.

```bash
git add src/main/java/com/mrsmith/config/AgentConfig.java \
        src/main/java/com/mrsmith/config/ConfigLoader.java \
        src/main/java/com/mrsmith/tool/ToolRegistry.java \
        src/main/java/com/mrsmith/cli/ChatCommand.java \
        src/test/java/com/mrsmith/config/ConfigLoaderTest.java \
        src/test/java/com/mrsmith/tool/ToolRegistryTest.java
git commit -m "feat: plumb per-agent shell command classification config"
```

---

## Task 5: Documentation

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the shell tool row in the tools table**

In `README.md`, replace the `shell` row (around line 174) with:

```markdown
| `shell` | no | Runs `bash -c <command>` in the CWD; returns stdout, stderr, exit code (30s timeout). Read-only commands (`ls`, `cat`, `git status`, ...) run automatically; commands that modify the filesystem or unknown commands require approval. The `a`/`always` option is per-command (e.g. `git commit`) |
```

- [ ] **Step 2: Document the config fields**

In `README.md`, in the "Fields" table after the `agents[].tools` row (around line 115), add:

```markdown
| `agents[].shellHarmlessCommands` | Command specs promoted to read-only on top of the built-in defaults (e.g. `"kubectl get"`); one token (`"ps"`) allows the whole binary, two tokens allow that subcommand only |
| `agents[].shellDangerousCommands` | Command specs forced to require approval, taking precedence over the safe lists (e.g. `"mydeploy --push"`) |
```

- [ ] **Step 3: Update the tools overview paragraph**

In `README.md`, replace the sentence "**Read-only tools run automatically; anything that modifies the filesystem prompts for confirmation** (`y/N`)." (around line 167) with:

```markdown
**Read-only tools run automatically; anything that modifies the filesystem prompts for confirmation** (`y/N`, or `a` to always allow). The `shell` tool further classifies each command: read-only commands run automatically, while filesystem-modifying or unknown commands prompt.
```

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: document shell command classification and config"
```
