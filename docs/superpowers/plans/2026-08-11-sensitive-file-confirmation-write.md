# Sensitive-File Confirmation for Write Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `write_file` and `edit` prompt for confirmation only when targeting a sensitive file, and stop prompting for ordinary in-sandbox writes.

**Architecture:** Reuse the existing `SensitivePaths` matcher. Each write tool overrides `approvalCheck(args)` to return an `ApprovalCheck` when the target is sensitive and `null` otherwise. Outside-root paths remain a hard refusal via `ToolPaths` (unchanged).

**Tech Stack:** Java 21, Maven, JUnit 5 (junit-jupiter 5.10.2), Jackson.

---

### Task 1: `WriteFileTool` confirmation

**Files:**
- Modify: `src/main/java/com/mrsmith/tool/WriteFileTool.java`
- Test: `src/test/java/com/mrsmith/tool/WriteFileToolTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mrsmith/tool/WriteFileToolTest.java`:

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

class WriteFileToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode args(String path) {
        ObjectNode node = JSON.createObjectNode();
        node.put("path", path);
        node.put("content", "x");
        return node;
    }

    @Test
    void noCheckForNormalFile(@TempDir Path root) {
        assertNull(new WriteFileTool(root).approvalCheck(args("src/Main.java")));
    }

    @Test
    void checkForDotEnv(@TempDir Path root) {
        assertNotNull(new WriteFileTool(root).approvalCheck(args(".env")));
    }

    @Test
    void checkForSshKey(@TempDir Path root) {
        assertNotNull(new WriteFileTool(root).approvalCheck(args("config/id_rsa")));
    }

    @Test
    void checkForPem(@TempDir Path root) {
        assertNotNull(new WriteFileTool(root).approvalCheck(args("certs/server.pem")));
    }

    @Test
    void noCheckForEscapingPath(@TempDir Path root) {
        assertNull(new WriteFileTool(root).approvalCheck(args("../outside.txt")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o test -Dtest=WriteFileToolTest`
Expected: FAIL — `approvalCheck` returns a non-null check for `src/Main.java` (default `Tool.approvalCheck` returns a check for non-read-only tools).

- [ ] **Step 3: Write minimal implementation**

In `src/main/java/com/mrsmith/tool/WriteFileTool.java`, add an `approvalCheck` override after `isReadOnly()`:

```java
    @Override
    public Tool.ApprovalCheck approvalCheck(JsonNode args) {
        String pathArg = args.path("path").asText(null);
        if (pathArg == null || pathArg.isBlank()) {
            return null;
        }
        try {
            Path target = ToolPaths.requireWithin(root, pathArg);
            return SensitivePaths.isSensitive(target) ? new Tool.ApprovalCheck(List.of(name()), "sensitive file") : null;
        } catch (ToolException e) {
            return null;
        }
    }
```

Add the import `java.util.List;` to the existing import block.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -o test -Dtest=WriteFileToolTest`
Expected: PASS (all 5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/tool/WriteFileTool.java src/test/java/com/mrsmith/tool/WriteFileToolTest.java
git commit -m "feat: confirm before writing sensitive files"
```

---

### Task 2: `EditTool` confirmation

**Files:**
- Modify: `src/main/java/com/mrsmith/tool/EditTool.java`
- Test: `src/test/java/com/mrsmith/tool/EditToolTest.java`

- [ ] **Step 1: Write the failing test**

Add the following tests to the existing `src/test/java/com/mrsmith/tool/EditToolTest.java` (before the final closing brace), and add the imports `com.fasterxml.jackson.databind.JsonNode`, `com.fasterxml.jackson.databind.node.ObjectNode`, `assertNotNull`, and `assertNull`:

```java
    private JsonNode editArgs(String filePath) {
        ObjectNode node = JSON.createObjectNode();
        node.put("filePath", filePath);
        node.put("oldString", "x");
        node.put("newString", "y");
        return node;
    }

    @Test
    void noCheckForNormalFile() {
        assertNull(tool().approvalCheck(editArgs("a.txt")));
    }

    @Test
    void checkForDotEnv() {
        assertNotNull(tool().approvalCheck(editArgs(".env")));
    }

    @Test
    void noCheckForEscapingPath() {
        assertNull(tool().approvalCheck(editArgs("../outside.txt")));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o test -Dtest=EditToolTest`
Expected: FAIL — `approvalCheck` returns a non-null check for `a.txt` (default `Tool.approvalCheck` returns a check for non-read-only tools).

- [ ] **Step 3: Write minimal implementation**

In `src/main/java/com/mrsmith/tool/EditTool.java`, add an `approvalCheck` override after `isReadOnly()`:

```java
    @Override
    public Tool.ApprovalCheck approvalCheck(JsonNode args) {
        String pathArg = args.path("filePath").asText(null);
        if (pathArg == null || pathArg.isBlank()) {
            return null;
        }
        try {
            Path target = ToolPaths.requireWithin(root, pathArg);
            return SensitivePaths.isSensitive(target) ? new Tool.ApprovalCheck(List.of(name()), "sensitive file") : null;
        } catch (ToolException e) {
            return null;
        }
    }
```

Add the import `java.util.List;` to the existing import block.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -o test -Dtest=EditToolTest`
Expected: PASS (all existing execute tests + 3 new approvalCheck tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/tool/EditTool.java src/test/java/com/mrsmith/tool/EditToolTest.java
git commit -m "feat: confirm before editing sensitive files"
```

---

### Task 3: Full suite verification

**Files:** none

- [ ] **Step 1: Run the full test suite**

Run: `mvn -o test`
Expected: BUILD SUCCESS, all tests pass (existing + new).

- [ ] **Step 2: Confirm no regressions in approval flow**

The existing `ShellToolTest`, `ToolRegistryTest`, `ChatSessionTest`, and the read-tool tests (`ReadFileToolTest`, `ListDirToolTest`, `GlobToolTest`, `SensitivePathsTest`) must still pass unchanged.

- [ ] **Step 3: Commit any incidental fixes**

If any test failed and was fixed, commit with a descriptive message. If nothing changed, skip this step.
