# Sensitive-File Confirmation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prompt the user for confirmation before `read_file`, `glob`, or `list_dir` touches a sensitive file (`.env*`, `id_rsa`, `*.pem|key|p12|pfx|jks|keystore`) inside the working directory.

**Architecture:** Add a shared `SensitivePaths` matcher (package-private, mirroring `ToolPaths`). Each of the three file-inspection tools overrides `approvalCheck(args)` to return an `ApprovalCheck` when the target is sensitive, reusing the existing ToolLoop `[y/N/a=always]` prompt. Outside-root paths remain a hard refusal via `ToolPaths`.

**Tech Stack:** Java 21, Maven, JUnit 5 (junit-jupiter 5.10.2), Jackson.

---

### Task 1: `SensitivePaths` matcher

**Files:**
- Create: `src/main/java/com/mrsmith/tool/SensitivePaths.java`
- Test: `src/test/java/com/mrsmith/tool/SensitivePathsTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mrsmith/tool/SensitivePathsTest.java`:

```java
package com.mrsmith.tool;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitivePathsTest {

    @Test
    void dotEnvIsSensitive() {
        assertTrue(SensitivePaths.isSensitive(Path.of(".env")));
    }

    @Test
    void dotEnvVariantIsSensitive() {
        assertTrue(SensitivePaths.isSensitive(Path.of(".env.local")));
    }

    @Test
    void nestedDotEnvIsSensitive() {
        assertTrue(SensitivePaths.isSensitive(Path.of("config", ".env")));
    }

    @Test
    void sshKeyIsSensitive() {
        assertTrue(SensitivePaths.isSensitive(Path.of("config", "id_rsa")));
    }

    @Test
    void pemIsSensitive() {
        assertTrue(SensitivePaths.isSensitive(Path.of("certs", "server.pem")));
    }

    @Test
    void keyIsSensitive() {
        assertTrue(SensitivePaths.isSensitive(Path.of("a.key")));
    }

    @Test
    void p12IsSensitive() {
        assertTrue(SensitivePaths.isSensitive(Path.of("b.p12")));
    }

    @Test
    void caseInsensitive() {
        assertTrue(SensitivePaths.isSensitive(Path.of("Certs", "SERVER.PEM")));
    }

    @Test
    void normalFileIsNotSensitive() {
        assertFalse(SensitivePaths.isSensitive(Path.of("README.md")));
    }

    @Test
    void javaSourceIsNotSensitive() {
        assertFalse(SensitivePaths.isSensitive(Path.of("src", "Main.java")));
    }

    @Test
    void txtIsNotSensitive() {
        assertFalse(SensitivePaths.isSensitive(Path.of("notes.txt")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o test -Dtest=SensitivePathsTest`
Expected: FAIL — `SensitivePaths` cannot be resolved (does not exist).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/mrsmith/tool/SensitivePaths.java`:

```java
package com.mrsmith.tool;

import java.nio.file.Path;
import java.util.regex.Pattern;

final class SensitivePaths {

    private static final Pattern SENSITIVE = Pattern.compile(
            "(^|.*[/\\\\])\\.env($|[.].*)|"
                    + "(^|.*[/\\\\])(id_rsa|id_dsa|id_ecdsa|id_ed25519)([.]|$)|"
                    + "(^|.*[/\\\\])[^/\\\\]*\\.(pem|key|p12|pfx|jks|keystore)$",
            Pattern.CASE_INSENSITIVE);

    private SensitivePaths() {
    }

    static boolean isSensitive(Path path) {
        return SENSITIVE.matcher(path.toString()).matches();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -o test -Dtest=SensitivePathsTest`
Expected: PASS (all 11 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/tool/SensitivePaths.java src/test/java/com/mrsmith/tool/SensitivePathsTest.java
git commit -m "feat: add sensitive-path matcher for file-inspection tools"
```

---

### Task 2: `ReadFileTool` confirmation

**Files:**
- Modify: `src/main/java/com/mrsmith/tool/ReadFileTool.java`
- Test: `src/test/java/com/mrsmith/tool/ReadFileToolTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mrsmith/tool/ReadFileToolTest.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReadFileToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode args(String path) {
        ObjectNode node = JSON.createObjectNode();
        node.put("path", path);
        return node;
    }

    @Test
    void noCheckForNormalFile(@TempDir Path root) {
        assertNull(new ReadFileTool(root).approvalCheck(args("README.md")));
    }

    @Test
    void checkForDotEnv(@TempDir Path root) {
        assertNotNull(new ReadFileTool(root).approvalCheck(args(".env")));
    }

    @Test
    void checkForDotEnvVariant(@TempDir Path root) {
        assertNotNull(new ReadFileTool(root).approvalCheck(args(".env.local")));
    }

    @Test
    void checkForSshKey(@TempDir Path root) {
        assertNotNull(new ReadFileTool(root).approvalCheck(args("config/id_rsa")));
    }

    @Test
    void checkForPem(@TempDir Path root) {
        assertNotNull(new ReadFileTool(root).approvalCheck(args("certs/server.pem")));
    }

    @Test
    void noCheckForEscapingPath(@TempDir Path root) {
        assertNull(new ReadFileTool(root).approvalCheck(args("../outside.txt")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o test -Dtest=ReadFileToolTest`
Expected: FAIL — `approvalCheck` returns `null` for `.env` (default `Tool.approvalCheck` returns `null` for read-only tools).

- [ ] **Step 3: Write minimal implementation**

In `src/main/java/com/mrsmith/tool/ReadFileTool.java`, add an `approvalCheck` override after `isReadOnly()`:

```java
    @Override
    public Tool.ApprovalCheck approvalCheck(JsonNode args) {
        String pathArg = args.path("path").asText(null);
        if (pathArg == null || pathArg.isBlank()) {
            return null;
        }
        try {
            Path target = ToolPaths.requireCanonicalWithin(root, ToolPaths.requireWithin(root, pathArg));
            return SensitivePaths.isSensitive(target) ? new Tool.ApprovalCheck(List.of(name()), "sensitive file") : null;
        } catch (ToolException e) {
            return null;
        }
    }
```

Add the import `java.util.List;` to the existing import block.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -o test -Dtest=ReadFileToolTest`
Expected: PASS (all 6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/tool/ReadFileTool.java src/test/java/com/mrsmith/tool/ReadFileToolTest.java
git commit -m "feat: confirm before reading sensitive files"
```

---

### Task 3: `ListDirTool` confirmation

**Files:**
- Modify: `src/main/java/com/mrsmith/tool/ListDirTool.java`
- Test: `src/test/java/com/mrsmith/tool/ListDirToolTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mrsmith/tool/ListDirToolTest.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ListDirToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode args(String path) {
        ObjectNode node = JSON.createObjectNode();
        node.put("path", path);
        return node;
    }

    @Test
    void noCheckForEmptyDir(@TempDir Path root) {
        assertNull(new ListDirTool(root).approvalCheck(args(".")));
    }

    @Test
    void noCheckForDirWithNormalFiles(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("README.md"), "hi");
        assertNull(new ListDirTool(root).approvalCheck(args(".")));
    }

    @Test
    void checkForDirContainingDotEnv(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".env"), "SECRET=1");
        assertNotNull(new ListDirTool(root).approvalCheck(args(".")));
    }

    @Test
    void checkForSensitiveDirItself(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".env"));
        assertNotNull(new ListDirTool(root).approvalCheck(args(".env")));
    }

    @Test
    void noCheckForMissingDir(@TempDir Path root) {
        assertNull(new ListDirTool(root).approvalCheck(args("nope")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o test -Dtest=ListDirToolTest`
Expected: FAIL — `approvalCheck` returns `null` for the dir containing `.env`.

- [ ] **Step 3: Write minimal implementation**

In `src/main/java/com/mrsmith/tool/ListDirTool.java`, add an `approvalCheck` override after `isReadOnly()`:

```java
    @Override
    public Tool.ApprovalCheck approvalCheck(JsonNode args) {
        String pathArg = args.path("path").asText(null);
        if (pathArg == null || pathArg.isBlank()) {
            return null;
        }
        try {
            Path dir = ToolPaths.requireCanonicalWithin(root, ToolPaths.requireWithin(root, pathArg));
            if (!Files.isDirectory(dir)) {
                return null;
            }
            if (SensitivePaths.isSensitive(dir)) {
                return new Tool.ApprovalCheck(List.of(name()), "sensitive file");
            }
            try (Stream<Path> stream = Files.list(dir)) {
                boolean sensitive = stream.anyMatch(p -> SensitivePaths.isSensitive(p.getFileName()));
                return sensitive ? new Tool.ApprovalCheck(List.of(name()), "sensitive file") : null;
            } catch (IOException e) {
                return null;
            }
        } catch (ToolException e) {
            return null;
        }
    }
```

Add imports `java.util.List;` and `java.io.IOException;` to the existing import block.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -o test -Dtest=ListDirToolTest`
Expected: PASS (all 5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/tool/ListDirTool.java src/test/java/com/mrsmith/tool/ListDirToolTest.java
git commit -m "feat: confirm before listing directories containing sensitive files"
```

---

### Task 4: `GlobTool` confirmation

**Files:**
- Modify: `src/main/java/com/mrsmith/tool/GlobTool.java`
- Test: `src/test/java/com/mrsmith/tool/GlobToolTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mrsmith/tool/GlobToolTest.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlobToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode args(String pattern) {
        ObjectNode node = JSON.createObjectNode();
        node.put("pattern", pattern);
        return node;
    }

    @Test
    void noCheckForLiteralPath(@TempDir Path root) {
        assertNull(new GlobTool(root).approvalCheck(args("README.md")));
    }

    @Test
    void checkForStar(@TempDir Path root) {
        assertNotNull(new GlobTool(root).approvalCheck(args("*")));
    }

    @Test
    void checkForDoubleStar(@TempDir Path root) {
        assertNotNull(new GlobTool(root).approvalCheck(args("**/*.java")));
    }

    @Test
    void checkForEnvGlob(@TempDir Path root) {
        assertNotNull(new GlobTool(root).approvalCheck(args("*.env")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o test -Dtest=GlobToolTest`
Expected: FAIL — `approvalCheck` returns `null` for `*`.

- [ ] **Step 3: Write minimal implementation**

In `src/main/java/com/mrsmith/tool/GlobTool.java`, add an `approvalCheck` override after `isReadOnly()`:

```java
    @Override
    public Tool.ApprovalCheck approvalCheck(JsonNode args) {
        String pattern = args.path("pattern").asText(null);
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        boolean couldMatchSensitive = pattern.indexOf('*') >= 0
                || pattern.indexOf('?') >= 0
                || pattern.indexOf('[') >= 0
                || pattern.indexOf('{') >= 0;
        return couldMatchSensitive ? new Tool.ApprovalCheck(List.of(name()), "pattern may match sensitive files") : null;
    }
```

Add the import `java.util.List;` to the existing import block.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -o test -Dtest=GlobToolTest`
Expected: PASS (all 4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/tool/GlobTool.java src/test/java/com/mrsmith/tool/GlobToolTest.java
git commit -m "feat: confirm before glob patterns that may match sensitive files"
```

---

### Task 5: Full suite verification

**Files:** none

- [ ] **Step 1: Run the full test suite**

Run: `mvn -o test`
Expected: BUILD SUCCESS, all tests pass (existing + new).

- [ ] **Step 2: Confirm no regressions in approval flow**

The existing `ShellToolTest`, `ToolRegistryTest`, and `ChatSessionTest` approval tests must still pass unchanged (they cover the shared ToolLoop confirm path).

- [ ] **Step 3: Commit any incidental fixes**

If any test failed and was fixed, commit with a descriptive message. If nothing changed, skip this step.
