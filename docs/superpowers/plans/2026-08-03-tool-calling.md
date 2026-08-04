# Tool Calling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the model request actions (tool calls) that Mr Smith executes and feeds back, looping until a final answer, with per-agent tool opt-in and approval for destructive tools.

**Architecture:** Add a `com.mrsmith.tool` package (Tool interface, ToolRegistry, six built-in tools). Extend the OpenAI-compatible wire layer (`ChatMessage`/`Role`/`SseParser`/`OpenAiCompatibleProvider`) to carry `tools`, `tool_calls`, and `role:tool` messages. Add an inner tool loop to `ChatSession` (8-round cap), per-agent `tools` config, and `tool_call`/`tool_result` transcript records.

**Tech Stack:** Java 21, JUnit 5 (Jupiter), Jackson, JDK `java.net.http.HttpClient`, OkHttp MockWebServer (test), picocli.

---

## File Structure

**Create (main):**
- `src/main/java/com/mrsmith/tool/Tool.java`
- `src/main/java/com/mrsmith/tool/ToolException.java`
- `src/main/java/com/mrsmith/tool/ToolResult.java`
- `src/main/java/com/mrsmith/tool/ToolPaths.java`
- `src/main/java/com/mrsmith/tool/ReadFileTool.java`
- `src/main/java/com/mrsmith/tool/WriteFileTool.java`
- `src/main/java/com/mrsmith/tool/ListDirTool.java`
- `src/main/java/com/mrsmith/tool/GlobTool.java`
- `src/main/java/com/mrsmith/tool/ShellTool.java`
- `src/main/java/com/mrsmith/tool/WebFetchTool.java`
- `src/main/java/com/mrsmith/tool/ToolRegistry.java`
- `src/main/java/com/mrsmith/tool/ToolRegistryFactory.java`
- `src/main/java/com/mrsmith/provider/ToolCall.java`

**Modify (main):**
- `src/main/java/com/mrsmith/provider/ChatMessage.java`
- `src/main/java/com/mrsmith/provider/Role.java`
- `src/main/java/com/mrsmith/provider/SseResult.java`
- `src/main/java/com/mrsmith/provider/SseParser.java`
- `src/main/java/com/mrsmith/provider/Provider.java`
- `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java`
- `src/main/java/com/mrsmith/chat/ContextBuilder.java`
- `src/main/java/com/mrsmith/chat/FullContextBuilder.java`
- `src/main/java/com/mrsmith/chat/ChatSession.java`
- `src/main/java/com/mrsmith/session/TranscriptWriter.java`
- `src/main/java/com/mrsmith/session/FileTranscriptWriter.java`
- `src/main/java/com/mrsmith/config/AgentConfig.java`
- `src/main/java/com/mrsmith/config/AppConfig.java`
- `src/main/java/com/mrsmith/config/ConfigLoader.java`
- `src/main/java/com/mrsmith/config/AgentCatalog.java`
- `src/main/java/com/mrsmith/cli/ChatCommand.java`

**Create (test):**
- `src/test/java/com/mrsmith/tool/FileToolsTest.java`
- `src/test/java/com/mrsmith/tool/ShellToolTest.java`
- `src/test/java/com/mrsmith/tool/WebFetchToolTest.java`
- `src/test/java/com/mrsmith/tool/ToolRegistryTest.java`
- `src/test/java/com/mrsmith/provider/ToolCallTest.java`

**Modify (test):**
- `src/test/java/com/mrsmith/provider/ChatMessageTest.java`
- `src/test/java/com/mrsmith/provider/RoleTest.java`
- `src/test/java/com/mrsmith/provider/SseParserTest.java`
- `src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java`
- `src/test/java/com/mrsmith/chat/FullContextBuilderTest.java`
- `src/test/java/com/mrsmith/chat/ChatSessionTest.java`
- `src/test/java/com/mrsmith/session/FileTranscriptWriterTest.java`
- `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`
- `src/test/java/com/mrsmith/config/AgentCatalogTest.java`

---

### Task 1: DTO layer — Tool domain model + message/Role changes

**Files:**
- Create: `src/main/java/com/mrsmith/tool/Tool.java`, `ToolException.java`, `ToolResult.java`
- Create: `src/main/java/com/mrsmith/provider/ToolCall.java`
- Modify: `src/main/java/com/mrsmith/provider/Role.java`, `src/main/java/com/mrsmith/provider/ChatMessage.java`
- Test: `src/test/java/com/mrsmith/provider/ToolCallTest.java`, `src/test/java/com/mrsmith/provider/ChatMessageTest.java`, `src/test/java/com/mrsmith/provider/RoleTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/mrsmith/provider/ToolCallTest.java`:

```java
package com.mrsmith.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolCallTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void carriesIdNameAndArguments() {
        JsonNode args = JSON.readTree("{\"command\":\"ls\"}");
        ToolCall call = new ToolCall("call_1", "shell", args);
        assertEquals("call_1", call.id());
        assertEquals("shell", call.name());
        assertEquals(args, call.arguments());
    }
}
```

Add `TOOL` role assertions to `src/test/java/com/mrsmith/provider/RoleTest.java` (read the current file first, then add):

```java
    @Test
    void toolRoleHasApiName() {
        assertEquals("tool", Role.TOOL.apiName());
    }
```

Update `src/test/java/com/mrsmith/provider/ChatMessageTest.java` (read it first; keep existing tests, add):

```java
    @Test
    void carriesToolCallsAndToolCallId() {
        ChatMessage assistant = new ChatMessage(Role.ASSISTANT, null, null, List.of(), null);
        assertEquals(Role.ASSISTANT, assistant.role());
        ChatMessage toolResult = new ChatMessage(Role.TOOL, "42", null, null, "call_1");
        assertEquals("call_1", toolResult.toolCallId());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=ToolCallTest,RoleTest,ChatMessageTest`
Expected: FAIL — `Role.TOOL` and `ToolCall` do not exist, `ChatMessage` has no `toolCallId`/`toolCalls`.

- [ ] **Step 3: Create the Tool domain types**

Create `src/main/java/com/mrsmith/tool/Tool.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;

public interface Tool {

    String name();

    String description();

    JsonNode parametersSchema();

    boolean isReadOnly();

    ToolResult execute(JsonNode args);
}
```

Create `src/main/java/com/mrsmith/tool/ToolException.java`:

```java
package com.mrsmith.tool;

public class ToolException extends RuntimeException {

    public ToolException(String message) {
        super(message);
    }
}
```

Create `src/main/java/com/mrsmith/tool/ToolResult.java`:

```java
package com.mrsmith.tool;

public record ToolResult(String content, boolean error) {
}
```

Create `src/main/java/com/mrsmith/provider/ToolCall.java`:

```java
package com.mrsmith.provider;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolCall(String id, String name, JsonNode arguments) {
}
```

- [ ] **Step 4: Modify Role**

Replace `src/main/java/com/mrsmith/provider/Role.java` with:

```java
package com.mrsmith.provider;

public enum Role {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    private final String apiName;

    Role(String apiName) {
        this.apiName = apiName;
    }

    public String apiName() {
        return apiName;
    }
}
```

- [ ] **Step 5: Modify ChatMessage**

Replace `src/main/java/com/mrsmith/provider/ChatMessage.java` with:

```java
package com.mrsmith.provider;

import java.util.List;

public record ChatMessage(Role role, String content, String thinking,
                          List<ToolCall> toolCalls, String toolCallId) {

    public ChatMessage(Role role, String content) {
        this(role, content, null, null, null);
    }

    public ChatMessage(Role role, String content, String thinking) {
        this(role, content, thinking, null, null);
    }

    public String roleName() {
        return role.apiName();
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn test -Dtest=ToolCallTest,RoleTest,ChatMessageTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mrsmith/tool src/main/java/com/mrsmith/provider/ToolCall.java \
        src/main/java/com/mrsmith/provider/Role.java src/main/java/com/mrsmith/provider/ChatMessage.java \
        src/test/java/com/mrsmith/provider/ToolCallTest.java
git commit -m "feat: add tool domain model and TOOL role"
```

---

### Task 2: File tools (read_file, write_file, list_dir, glob)

**Files:**
- Create: `src/main/java/com/mrsmith/tool/ToolPaths.java`
- Create: `src/main/java/com/mrsmith/tool/ReadFileTool.java`, `WriteFileTool.java`, `ListDirTool.java`, `GlobTool.java`
- Test: `src/test/java/com/mrsmith/tool/FileToolsTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mrsmith/tool/FileToolsTest.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void readFileReturnsContents() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Files.writeString(root.resolve("a.txt"), "hello");
        ReadFileTool tool = new ReadFileTool(root);
        ToolResult result = tool.execute(JSON.readTree("{\"path\":\"a.txt\"}"));
        assertFalse(result.error());
        assertEquals("hello", result.content());
    }

    @Test
    void readFileRejectsEscapeOutsideRoot() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "secret");
        ReadFileTool tool = new ReadFileTool(root);
        ToolResult result = tool.execute(JSON.readTree("{\"path\":\"../outside.txt\"}"));
        assertTrue(result.error());
        assertTrue(result.content().contains("escapes"));
    }

    @Test
    void writeFileCreatesParentsAndWrites() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        WriteFileTool tool = new WriteFileTool(root);
        ToolResult result = tool.execute(JSON.readTree("{\"path\":\"sub/deep.txt\",\"content\":\"data\"}"));
        assertFalse(result.error());
        assertEquals("data", Files.readString(root.resolve("sub/deep.txt")));
    }

    @Test
    void listDirListsEntries() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Files.writeString(root.resolve("a.txt"), "");
        Files.writeString(root.resolve("b.txt"), "");
        ListDirTool tool = new ListDirTool(root);
        ToolResult result = tool.execute(JSON.readTree("{\"path\":\".\"}"));
        assertFalse(result.error());
        assertTrue(result.content().contains("a.txt"));
        assertTrue(result.content().contains("b.txt"));
    }

    @Test
    void globMatchesRecursively() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Files.createDirectory(root.resolve("src"));
        Files.writeString(root.resolve("src/Main.java"), "");
        Files.writeString(root.resolve("src/Util.java"), "");
        GlobTool tool = new GlobTool(root);
        ToolResult result = tool.execute(JSON.readTree("{\"pattern\":\"src/**/*.java\"}"));
        assertFalse(result.error());
        assertTrue(result.content().contains("src/Main.java"));
        assertTrue(result.content().contains("src/Util.java"));
    }

    @Test
    void readFileRejectsSymlinkEscape() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Path secret = tempDir.resolve("secret.txt");
        Files.writeString(secret, "secret");
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), secret);
            ReadFileTool tool = new ReadFileTool(root);
            ToolResult result = tool.execute(JSON.readTree("{\"path\":\"link.txt\"}"));
            assertTrue(result.error());
        } catch (UnsupportedOperationException e) {
            // filesystem without symlink support: skip
        }
    }

    @Test
    void missingArgumentThrowsToolException() {
        ReadFileTool tool = new ReadFileTool(tempDir);
        assertThrows(ToolException.class, () -> tool.execute(JSON.readTree("{}")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=FileToolsTest`
Expected: FAIL — tool classes do not exist.

- [ ] **Step 3: Write the path helper**

Create `src/main/java/com/mrsmith/tool/ToolPaths.java`:

```java
package com.mrsmith.tool;

import java.io.IOException;
import java.nio.file.Path;

final class ToolPaths {

    private ToolPaths() {
    }

    static Path requireWithin(Path root, String pathArg) {
        if (pathArg == null || pathArg.isBlank()) {
            throw new ToolException("missing required path argument");
        }
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(pathArg).normalize();
        if (!target.startsWith(base)) {
            throw new ToolException("path escapes the working directory: " + pathArg);
        }
        return target;
    }

    static Path requireCanonicalWithin(Path root, Path target) {
        try {
            Path baseReal = root.toRealPath();
            Path targetReal = target.toRealPath();
            if (!targetReal.startsWith(baseReal)) {
                throw new ToolException("path resolves outside the working directory: " + target);
            }
            return targetReal;
        } catch (IOException e) {
            throw new ToolException("could not resolve path: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: Write the four file tools**

Create `src/main/java/com/mrsmith/tool/ReadFileTool.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ReadFileTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_BYTES = 1_048_576;

    private final Path root;

    public ReadFileTool() {
        this(Path.of("").toAbsolutePath());
    }

    public ReadFileTool(Path root) {
        this.root = root;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read the contents of a file inside the working directory.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", JSON.createObjectNode()
                .set("path", JSON.createObjectNode().put("type", "string")));
        schema.set("required", JSON.createArrayNode().add("path"));
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        Path target = ToolPaths.requireWithin(root, args.path("path").asText(null));
        target = ToolPaths.requireCanonicalWithin(root, target);
        try {
            if (Files.size(target) > MAX_BYTES) {
                return new ToolResult("file exceeds " + MAX_BYTES + " bytes", true);
            }
            return new ToolResult(Files.readString(target), false);
        } catch (IOException e) {
            throw new ToolException("could not read file: " + e.getMessage(), e);
        }
    }
}
```

Create `src/main/java/com/mrsmith/tool/WriteFileTool.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WriteFileTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path root;

    public WriteFileTool() {
        this(Path.of("").toAbsolutePath());
    }

    public WriteFileTool(Path root) {
        this.root = root;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Write content to a file inside the working directory, creating parent directories as needed.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", JSON.createObjectNode()
                .set("path", JSON.createObjectNode().put("type", "string"))
                .set("content", JSON.createObjectNode().put("type", "string")));
        schema.set("required", JSON.createArrayNode().add("path").add("content"));
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String content = args.path("content").asText(null);
        if (content == null) {
            throw new ToolException("missing required 'content' argument");
        }
        String pathArg = args.path("path").asText(null);
        if (pathArg == null || pathArg.isBlank()) {
            throw new ToolException("missing required path argument");
        }
        Path target;
        try {
            target = ToolPaths.requireWithin(root, pathArg);
            Path parent = target.getParent();
            Path ancestor = target;
            while (ancestor != null && !Files.exists(ancestor) && !Files.isSymbolicLink(ancestor)) {
                ancestor = ancestor.getParent();
            }
            if (ancestor != null) {
                ToolPaths.requireCanonicalWithin(root, ancestor);
            }
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, content);
        } catch (ToolException e) {
            return new ToolResult(e.getMessage(), true);
        } catch (IOException e) {
            throw new ToolException("could not write file: " + e.getMessage(), e);
        }
        return new ToolResult("wrote " + root.relativize(target) + " (" + content.length() + " chars)", false);
    }
}
```

Note: `WriteFileTool.parametersSchema` is hoisted onto an `ObjectNode` local because Jackson 2.17's generic `ObjectNode.set(...)` breaks method chaining. The containment logic walks up from the target to the deepest existing/non-symlink ancestor and canonical-checks it, refusing any symlink (leaf, dangling, or intermediate directory) that resolves outside the working directory root. This prevents writing through symlinks that escape root.

Create `src/main/java/com/mrsmith/tool/ListDirTool.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ListDirTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path root;

    public ListDirTool() {
        this(Path.of("").toAbsolutePath());
    }

    public ListDirTool(Path root) {
        this.root = root;
    }

    @Override
    public String name() {
        return "list_dir";
    }

    @Override
    public String description() {
        return "List the entries of a directory inside the working directory.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", JSON.createObjectNode()
                .set("path", JSON.createObjectNode().put("type", "string")));
        schema.set("required", JSON.createArrayNode().add("path"));
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        Path dir = ToolPaths.requireCanonicalWithin(root, ToolPaths.requireWithin(root, args.path("path").asText(null)));
        if (!Files.isDirectory(dir)) {
            return new ToolResult("not a directory: " + dir, true);
        }
        try (Stream<Path> stream = Files.list(dir)) {
            String listing = stream.map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.joining("\n"));
            return new ToolResult(listing.isEmpty() ? "(empty)" : listing, false);
        } catch (IOException e) {
            throw new ToolException("could not list directory: " + e.getMessage(), e);
        }
    }
}
```

Create `src/main/java/com/mrsmith/tool/GlobTool.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class GlobTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path root;

    public GlobTool() {
        this(Path.of("").toAbsolutePath());
    }

    public GlobTool(Path root) {
        this.root = root;
    }

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "Find files matching a glob pattern relative to the working directory, e.g. src/**/*.java.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("pattern").put("type", "string");
        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String pattern = args.path("pattern").asText(null);
        if (pattern == null || pattern.isBlank()) {
            throw new ToolException("missing required 'pattern' argument");
        }
        Pattern regex = Pattern.compile(globToRegex(pattern));
        try (Stream<Path> stream = Files.walk(root)) {
            List<String> matches = stream.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p))
                    .map(p -> p.toString().replace('\\', '/'))
                    .filter(p -> regex.matcher(p).matches())
                    .sorted()
                    .toList();
            return new ToolResult(matches.isEmpty() ? "(no matches)" : String.join("\n", matches), false);
        } catch (IOException e) {
            throw new ToolException("could not glob: " + e.getMessage(), e);
        }
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                        regex.append("(?:[^/]+/)*");
                        i += 3;
                    } else {
                        regex.append(".*");
                        i += 2;
                    }
                } else {
                    regex.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return regex.toString();
    }
}
```

Note: `GlobTool` uses a glob-to-regex converter instead of `java.nio.file.PathMatcher`, because Java's `PathMatcher` `**` requires at least one directory level (`src/**/*.java` would not match `src/Main.java`), while the tests require bash-style `**` = zero-or-more directories.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=FileToolsTest`
Expected: PASS (all 8 tests)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/tool/ToolPaths.java src/main/java/com/mrsmith/tool/ReadFileTool.java \
        src/main/java/com/mrsmith/tool/WriteFileTool.java src/main/java/com/mrsmith/tool/ListDirTool.java \
        src/main/java/com/mrsmith/tool/GlobTool.java src/test/java/com/mrsmith/tool/FileToolsTest.java
git commit -m "feat: add file tools (read, write, list, glob)"
```

---

### Task 3: ShellTool + WebFetchTool

**Files:**
- Create: `src/main/java/com/mrsmith/tool/ShellTool.java`, `WebFetchTool.java`
- Test: `src/test/java/com/mrsmith/tool/ShellToolTest.java`, `src/test/java/com/mrsmith/tool/WebFetchToolTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/mrsmith/tool/ShellToolTest.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void returnsStdoutAndExitCodeZero() throws Exception {
        ShellTool tool = new ShellTool(tempDir, 5000);
        ToolResult result = tool.execute(JSON.readTree("{\"command\":\"echo hi\"}"));
        assertFalse(result.error());
        assertTrue(result.content().contains("hi"));
    }

    @Test
    void capturesNonZeroExitCode() throws Exception {
        ShellTool tool = new ShellTool(tempDir, 5000);
        ToolResult result = tool.execute(JSON.readTree("{\"command\":\"echo oops && exit 3\"}"));
        assertTrue(result.error());
        assertTrue(result.content().contains("3"));
    }

    @Test
    void runsInWorkingDirectory() throws Exception {
        Files.writeString(tempDir.resolve("marker.txt"), "present");
        ShellTool tool = new ShellTool(tempDir, 5000);
        ToolResult result = tool.execute(JSON.readTree("{\"command\":\"ls marker.txt\"}"));
        assertFalse(result.error());
        assertTrue(result.content().contains("marker.txt"));
    }

    @Test
    void timesOutAndReturnsError() throws Exception {
        ShellTool tool = new ShellTool(tempDir, 200);
        ToolResult result = tool.execute(JSON.readTree("{\"command\":\"sleep 5\"}"));
        assertTrue(result.error());
        assertTrue(result.content().contains("timed out"));
    }

    @Test
    void handlesLargeOutputWithoutDeadlock() throws Exception {
        ShellTool tool = new ShellTool(tempDir, 5000);
        ToolResult result = tool.execute(JSON.readTree("{\"command\":\"perl -e 'print \\\"x\\\" x 200000'\"}"));
        assertFalse(result.error());
        assertTrue(result.content().contains("x".repeat(200000)));
    }
}
}
```

Create `src/test/java/com/mrsmith/tool/WebFetchToolTest.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebFetchToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MockWebServer server;
    private WebFetchTool tool;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        tool = new WebFetchTool(client, 5000);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void fetchesBodyText() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("hello world"));
        ToolResult result = tool.execute(JSON.readTree("{\"url\":\"" + server.url("/page") + "\"}"));
        assertFalse(result.error());
        assertTrue(result.content().contains("hello world"));
    }

    @Test
    void followsRedirects() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", "/final"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("redirected"));
        ToolResult result = tool.execute(JSON.readTree("{\"url\":\"" + server.url("/start") + "\"}"));
        assertFalse(result.error());
        assertTrue(result.content().contains("redirected"));
    }

    @Test
    void returnsErrorOnHttp4xx() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("nope"));
        ToolResult result = tool.execute(JSON.readTree("{\"url\":\"" + server.url("/missing") + "\"}"));
        assertTrue(result.error());
        assertTrue(result.content().contains("404"));
    }

    @Test
    void timesOutWhenServerStalls() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        WebFetchTool slowTool = new WebFetchTool(HttpClient.newHttpClient(), 300);
        ToolResult result = slowTool.execute(JSON.readTree("{\"url\":\"" + server.url("/slow") + "\"}"));
        assertTrue(result.error());
    }

    @Test
    void malformedUrlThrowsToolException() {
        WebFetchTool tool = new WebFetchTool(HttpClient.newHttpClient(), 5000);
        assertThrows(ToolException.class,
                () -> tool.execute(JSON.readTree("{\"url\":\"http://\"}")));
    }
}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=ShellToolTest,WebFetchToolTest`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Write ShellTool**

Create `src/main/java/com/mrsmith/tool/ShellTool.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ShellTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path workDir;
    private final long timeoutMillis;

    public ShellTool() {
        this(Path.of("").toAbsolutePath(), 30_000L);
    }

    public ShellTool(Path workDir, long timeoutMillis) {
        this.workDir = workDir;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public String name() {
        return "shell";
    }

    @Override
    public String description() {
        return "Run a shell command via bash -c in the working directory and return its stdout, stderr, and exit code.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("command").put("type", "string");
        schema.putArray("required").add("command");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String command = args.path("command").asText(null);
        if (command == null || command.isBlank()) {
            throw new ToolException("missing required 'command' argument");
        }
        try {
            Process process = new ProcessBuilder("bash", "-c", command)
                    .directory(workDir.toFile())
                    .start();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
            CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ToolResult("shell command timed out after " + timeoutMillis + "ms", true);
            }
            String stdout = out.join();
            String stderr = err.join();
            int code = process.exitValue();
            String body = code == 0 ? stdout : (stdout.isBlank() ? stderr : stdout + "\n" + stderr);
            if (code != 0 && !body.isBlank()) {
                body = body + "\nexit code " + code;
            } else if (code != 0) {
                body = "exit code " + code;
            }
            return new ToolResult(body, code != 0);
        } catch (IOException e) {
            throw new ToolException("could not run command: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("command interrupted", e);
        }
    }

    private static String readAll(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(failed to read output: " + e.getMessage() + ")";
        }
    }
}
```

Note: `ShellTool` drains stdout/stderr concurrently with `waitFor` (via `CompletableFuture`), so commands producing more than the ~64KB pipe buffer exit normally instead of deadlocking on the timeout. The catch clause re-interrupts the thread only on `InterruptedException`.

- [ ] **Step 4: Write WebFetchTool**

Create `src/main/java/com/mrsmith/tool/WebFetchTool.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class WebFetchTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_BYTES = 1_048_576;

    private final HttpClient httpClient;
    private final long timeoutMillis;

    public WebFetchTool() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(), 10_000L);
    }

    public WebFetchTool(HttpClient httpClient, long timeoutMillis) {
        this.httpClient = httpClient;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public String description() {
        return "Fetch a URL over HTTP(S) and return the response body text.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("url").put("type", "string");
        schema.putArray("required").add("url");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String url = args.path("url").asText(null);
        if (url == null || url.isBlank()) {
            throw new ToolException("missing required 'url' argument");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new ToolException("url must start with http:// or https://");
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("User-Agent", "mr-smith")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw new ToolException("invalid url: " + url, e);
        }
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                return new ToolResult("HTTP " + response.statusCode(), true);
            }
            try (InputStream body = response.body()) {
                byte[] bytes = body.readNBytes((int) MAX_BYTES + 1);
                boolean truncated = bytes.length > MAX_BYTES;
                if (truncated) {
                    bytes = Arrays.copyOf(bytes, (int) MAX_BYTES);
                }
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (truncated) {
                    text = text + "\n[truncated]";
                }
                return new ToolResult(text, false);
            }
        } catch (IOException e) {
            return new ToolResult("fetch failed: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("fetch interrupted");
        }
    }
}
```

Add imports to WebFetchTool: `java.io.InputStream`, `java.nio.charset.StandardCharsets`, `java.util.Arrays`.

Note: `WebFetchTool` reads the response as an InputStream and caps the read at `MAX_BYTES` at the transport level (a huge body is never fully materialized in heap), validates the URL inside the try (malformed URLs become `ToolException`, which the session loop catches), and follows redirects via `HttpClient.Redirect.NORMAL`. Timeout (`HttpTimeoutException`) is an `IOException`, so it surfaces as a friendly error result.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=ShellToolTest,WebFetchToolTest`
Expected: PASS (5 shell + 5 web_fetch)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/tool/ShellTool.java src/main/java/com/mrsmith/tool/WebFetchTool.java \
        src/test/java/com/mrsmith/tool/ShellToolTest.java src/test/java/com/mrsmith/tool/WebFetchToolTest.java
git commit -m "feat: add shell and web_fetch tools"
```

---

### Task 4: ToolRegistry

**Files:**
- Create: `src/main/java/com/mrsmith/tool/ToolRegistry.java`
- Test: `src/test/java/com/mrsmith/tool/ToolRegistryTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mrsmith/tool/ToolRegistryTest.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static class StubTool implements Tool {
        private final String name;
        private final boolean readOnly;
        int calls = 0;

        StubTool(String name, boolean readOnly) {
            this.name = name;
            this.readOnly = readOnly;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public JsonNode parametersSchema() {
            return JSON.createObjectNode();
        }

        @Override
        public boolean isReadOnly() {
            return readOnly;
        }

        @Override
        public ToolResult execute(JsonNode args) {
            calls++;
            return new ToolResult("result", false);
        }
    }

    @Test
    void findsEnabledToolsByName() {
        StubTool shell = new StubTool("shell", false);
        ToolRegistry registry = new ToolRegistry(List.of(shell));
        assertEquals(Optional.of(shell), registry.find("shell"));
        assertTrue(registry.find("shell").isPresent());
        assertFalse(registry.find("web_fetch").isPresent());
    }

    @Test
    void exposesEnabledToolsInOrder() {
        StubTool a = new StubTool("read_file", true);
        StubTool b = new StubTool("shell", false);
        ToolRegistry registry = new ToolRegistry(List.of(b, a));
        assertEquals(List.of("shell", "read_file"),
                registry.tools().stream().map(Tool::name).toList());
    }

    @Test
    void emptyRegistryReportsEmpty() {
        ToolRegistry registry = new ToolRegistry(List.of());
        assertTrue(registry.isEmpty());
        assertTrue(registry.tools().isEmpty());
    }

    @Test
    void builtInWithNamesCreatesAllRequestedTools() {
        ToolRegistry registry = ToolRegistry.with(List.of("shell", "read_file", "write_file", "list_dir", "glob", "web_fetch"));
        assertEquals(6, registry.tools().size());
        assertTrue(registry.find("shell").isPresent());
        assertTrue(registry.find("web_fetch").isPresent());
    }

    @Test
    void builtInWithUnknownNameThrows() {
        assertThrows(ToolException.class, () -> ToolRegistry.with(List.of("nope")));
    }

    @Test
    void builtinNamesCoversAllTools() {
        assertTrue(ToolRegistry.builtinNames().containsAll(
                List.of("shell", "read_file", "write_file", "list_dir", "glob", "web_fetch")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ToolRegistryTest`
Expected: FAIL — `ToolRegistry` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/mrsmith/tool/ToolRegistry.java`:

```java
package com.mrsmith.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ToolRegistry {

    private final List<Tool> tools;
    private final Map<String, Tool> byName;

    public ToolRegistry(List<Tool> tools) {
        this.tools = List.copyOf(tools);
        Map<String, Tool> index = new LinkedHashMap<>();
        for (Tool tool : tools) {
            index.put(tool.name(), tool);
        }
        this.byName = Map.copyOf(index);
    }

    public static ToolRegistry with(List<String> toolNames) {
        List<Tool> tools = new ArrayList<>();
        for (String name : toolNames) {
            switch (name) {
                case "shell" -> tools.add(new ShellTool());
                case "read_file" -> tools.add(new ReadFileTool());
                case "write_file" -> tools.add(new WriteFileTool());
                case "list_dir" -> tools.add(new ListDirTool());
                case "glob" -> tools.add(new GlobTool());
                case "web_fetch" -> tools.add(new WebFetchTool());
                default -> throw new ToolException("Unknown tool: " + name);
            }
        }
        return new ToolRegistry(tools);
    }

    public static Set<String> builtinNames() {
        return Set.of("shell", "read_file", "write_file", "list_dir", "glob", "web_fetch");
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<Tool> tools() {
        return tools;
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=ToolRegistryTest`
Expected: PASS (all 6 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/tool/ToolRegistry.java src/test/java/com/mrsmith/tool/ToolRegistryTest.java
git commit -m "feat: add tool registry"
```

---

### Task 5: SSE parsing of tool_calls

**Files:**
- Modify: `src/main/java/com/mrsmith/provider/SseResult.java`, `src/main/java/com/mrsmith/provider/SseParser.java`
- Test: `src/test/java/com/mrsmith/provider/SseParserTest.java`

- [ ] **Step 1: Write the failing tests**

Append these tests to `src/test/java/com/mrsmith/provider/SseParserTest.java`:

```java
    @Test
    void accumulatesToolCallArgumentsAcrossChunks() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"shell","arguments":"{\\"command\\":\\""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ls\\"}"}}]}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), s -> { }, s -> { });
        assertEquals(1, result.toolCalls().size());
        ToolCall call = result.toolCalls().get(0);
        assertEquals("call_1", call.id());
        assertEquals("shell", call.name());
        assertEquals("ls", call.arguments().path("command").asText());
    }

    @Test
    void multipleToolCallsByIndex() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"read_file","arguments":"{\\"path\\":\\"a.txt\\"}"}},{"index":1,"id":"c2","function":{"name":"glob","arguments":"{\\"pattern\\":\\"**/*.java\\"}"}}]}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), s -> { }, s -> { });
        assertEquals(2, result.toolCalls().size());
        assertEquals("c1", result.toolCalls().get(0).id());
        assertEquals("read_file", result.toolCalls().get(0).name());
        assertEquals("c2", result.toolCalls().get(1).id());
        assertEquals("glob", result.toolCalls().get(1).name());
    }

    @Test
    void toolCallsNullWhenNonePresent() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"content":"plain answer"}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), s -> { }, s -> { });
        assertEquals(null, result.toolCalls());
        assertEquals("plain answer", result.content());
    }

    @Test
    void ignoresToolCallsWithMalformedArguments() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"shell","arguments":"not json"}}]}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), s -> { }, s -> { });
        assertEquals(1, result.toolCalls().size());
        assertEquals(0, result.toolCalls().get(0).arguments().size());
    }

    @Test
    void nonObjectArgumentsFallBackToEmptyObject() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"shell","arguments":"[1,2]"}}]}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), s -> { }, s -> { });
        assertEquals(1, result.toolCalls().size());
        assertEquals(0, result.toolCalls().get(0).arguments().size());
    }

    @Test
    void toolCallWithoutIdIsDropped() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"shell","arguments":"{}"}}]}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), s -> { }, s -> { });
        assertEquals(null, result.toolCalls());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=SseParserTest`
Expected: FAIL — `SseResult.toolCalls()` does not exist.

- [ ] **Step 3: Modify SseResult**

Replace `src/main/java/com/mrsmith/provider/SseResult.java` with:

```java
package com.mrsmith.provider;

import java.util.List;

public record SseResult(String content, String thinking, List<ToolCall> toolCalls, Usage usage) {
}
```

- [ ] **Step 4: Modify SseParser**

Replace the entire contents of `src/main/java/com/mrsmith/provider/SseParser.java` with:

```java
package com.mrsmith.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

public final class SseParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SseParser() {
    }

    public static SseResult consume(BufferedReader reader, Consumer<String> contentSink,
                                    Consumer<String> reasoningSink) throws IOException {
        StringBuilder content = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        boolean reasoningStreamed = false;
        boolean transitionNewlineSent = false;
        Usage usage = null;
        Map<Integer, ToolCallAccumulator> toolCalls = new TreeMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring("data:".length()).trim();
            if (payload.equals("[DONE]")) {
                break;
            }
            if (payload.isEmpty()) {
                continue;
            }
            JsonNode node = parse(payload);
            if (node == null) {
                continue;
            }
            Usage chunkUsage = extractUsage(node);
            if (chunkUsage != null) {
                usage = chunkUsage;
            }
            JsonNode delta = node.path("choices").path(0).path("delta");
            if (!delta.isMissingNode()) {
                String reasoning = extractReasoning(delta);
                if (reasoning != null && !reasoning.isEmpty()) {
                    reasoningSink.accept(reasoning);
                    thinking.append(reasoning);
                    reasoningStreamed = true;
                }
                String contentDelta = delta.path("content").asText(null);
                if (contentDelta != null && !contentDelta.isEmpty()) {
                    if (reasoningStreamed && !transitionNewlineSent) {
                        transitionNewlineSent = true;
                        contentSink.accept("\n");
                    }
                    contentSink.accept(contentDelta);
                    content.append(contentDelta);
                }
                accumulateToolCalls(delta, toolCalls);
            }
        }
        return new SseResult(content.toString(), thinking.isEmpty() ? null : thinking.toString(),
                buildToolCalls(toolCalls), usage);
    }

    private static void accumulateToolCalls(JsonNode delta, Map<Integer, ToolCallAccumulator> toolCalls) {
        JsonNode deltas = delta.path("tool_calls");
        if (!deltas.isArray()) {
            return;
        }
        for (JsonNode tc : deltas) {
            int index = tc.path("index").asInt();
            ToolCallAccumulator acc = toolCalls.computeIfAbsent(index, i -> new ToolCallAccumulator());
            if (tc.hasNonNull("id")) {
                acc.id = tc.get("id").asText();
            }
            JsonNode fn = tc.path("function");
            if (fn.isObject()) {
                if (fn.hasNonNull("name")) {
                    acc.name = fn.get("name").asText();
                }
                if (fn.hasNonNull("arguments")) {
                    acc.arguments.append(fn.get("arguments").asText());
                }
            }
        }
    }

    private static List<ToolCall> buildToolCalls(Map<Integer, ToolCallAccumulator> toolCalls) {
        if (toolCalls.isEmpty()) {
            return null;
        }
        List<ToolCall> calls = new ArrayList<>();
        for (ToolCallAccumulator acc : toolCalls.values()) {
            if (acc.id == null || acc.id.isBlank()) {
                continue;
            }
            calls.add(new ToolCall(acc.id, acc.name, parseArguments(acc.arguments.toString())));
        }
        return calls.isEmpty() ? null : calls;
    }

    private static JsonNode parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            JsonNode parsed = JSON.readTree(arguments);
            return parsed.isObject() ? parsed : JSON.createObjectNode();
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }

    private static String extractReasoning(JsonNode delta) {
        String reasoning = delta.path("reasoning_content").asText(null);
        if (reasoning == null) {
            reasoning = delta.path("reasoning").asText(null);
        }
        return reasoning;
    }

    private static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (IOException e) {
            System.err.println("Warning: malformed SSE chunk, skipping: " + payload);
            return null;
        }
    }

    private static Usage extractUsage(JsonNode node) {
        JsonNode usageNode = node.path("usage");
        if (usageNode.isMissingNode() || usageNode.isNull()) {
            return null;
        }
        Integer prompt = usageNode.hasNonNull("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : null;
        Integer completion = usageNode.hasNonNull("completion_tokens") ? usageNode.get("completion_tokens").asInt() : null;
        if (prompt == null && completion == null) {
            return null;
        }
        return new Usage(prompt, completion);
    }

    private static final class ToolCallAccumulator {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=SseParserTest`
Expected: PASS (all existing + 6 new tests)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/provider/SseResult.java src/main/java/com/mrsmith/provider/SseParser.java \
        src/test/java/com/mrsmith/provider/SseParserTest.java
git commit -m "feat: parse tool_calls from SSE stream"
```

---

### Task 6: Provider wire format — tools array + tool message serialization

**Files:**
- Modify: `src/main/java/com/mrsmith/provider/Provider.java`, `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java`
- Test: `src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java`

- [ ] **Step 1: Change the Provider interface**

Replace `src/main/java/com/mrsmith/provider/Provider.java` with:

```java
package com.mrsmith.provider;

import com.mrsmith.tool.Tool;

import java.util.List;
import java.util.function.Consumer;

public interface Provider {

    ProviderResponse send(List<ChatMessage> context, List<Tool> tools,
                          Consumer<String> tokenSink, Consumer<String> reasoningSink);
}
```

- [ ] **Step 2: Update OpenAiCompatibleProvider**

Modify `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java`:

1. Add import: `import com.mrsmith.tool.Tool;`.

2. Change the `send` and `doSend` methods (currently lines 42-62) to:

```java
    @Override
    public ProviderResponse send(List<ChatMessage> context, List<Tool> tools,
                                 Consumer<String> tokenSink, Consumer<String> reasoningSink) {
        try {
            return doSend(context, tools, tokenSink, reasoningSink);
        } catch (ProviderException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Network error while contacting "
                    + config.baseUrl() + ": " + e.getMessage(), e);
        }
    }

    private ProviderResponse doSend(List<ChatMessage> context, List<Tool> tools,
                                    Consumer<String> tokenSink, Consumer<String> reasoningSink)
            throws IOException, InterruptedException {
        HttpRequest request = buildRequest(buildRequestBody(context, tools));
        HttpResponse<InputStream> response = sendWithRetry(request);
        return handleResponse(response, context, tokenSink, reasoningSink);
    }
```

3. In `handleResponse`, change the message construction (currently line 103) from:

```java
            ChatMessage message = new ChatMessage(Role.ASSISTANT, result.content(), result.thinking());
```

to:

```java
            ChatMessage message = new ChatMessage(Role.ASSISTANT, result.content(), result.thinking(),
                    result.toolCalls(), null);
```

4. Replace the `buildRequestBody` method (currently lines 132-151) and add a `serializeMessage` helper:

```java
    private String buildRequestBody(List<ChatMessage> context, List<Tool> tools) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);
        if (config.includeUsage()) {
            root.putObject("stream_options").put("include_usage", true);
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = root.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode entry = toolsArray.addObject();
                entry.put("type", "function");
                ObjectNode fn = entry.putObject("function");
                fn.put("name", tool.name());
                fn.put("description", tool.description());
                fn.set("parameters", tool.parametersSchema());
            }
        }
        ArrayNode messages = root.putArray("messages");
        for (ChatMessage message : context) {
            messages.add(serializeMessage(message));
        }
        try {
            return JSON.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize request body", e);
        }
    }

    private ObjectNode serializeMessage(ChatMessage message) {
        ObjectNode node = JSON.createObjectNode();
        node.put("role", message.roleName());
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            node.putNull("content");
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolCall call : message.toolCalls()) {
                ObjectNode entry = calls.addObject();
                entry.put("id", call.id());
                entry.put("type", "function");
                ObjectNode fn = entry.putObject("function");
                fn.put("name", call.name());
                fn.put("arguments", call.arguments() == null ? "{}" : call.arguments().toString());
            }
            return node;
        }
        if (message.role() == Role.TOOL) {
            node.put("tool_call_id", message.toolCallId());
            node.put("content", message.content() == null ? "" : message.content());
            return node;
        }
        String content = message.content() == null ? "" : message.content();
        node.put("content", content);
        return node;
    }
```

Note: `ArrayNode` and `ObjectNode` are already imported in this file (lines 4-5).

- [ ] **Step 3: Update existing provider test call sites**

In `src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java`, every call to `provider.send(X, A, B)` must become `provider.send(X, List.of(), A, B)`. Apply this mechanically to the call sites at (original) lines 55, 65, 83, 94, 105, 115, 136, 160, 172, 189, 200, 214, 233, 246, 254, 273, 282. `List` is already imported.

- [ ] **Step 4: Add new provider tests**

Append these to `src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java`. Add imports: `import com.fasterxml.jackson.databind.JsonNode;`, `import com.fasterxml.jackson.databind.ObjectMapper;`, `import com.mrsmith.tool.Tool;`, `import com.mrsmith.tool.ToolResult;`.

```java
    private static final ObjectMapper JSON = new ObjectMapper();

    static class StubTool implements Tool {
        private final String name;
        private final JsonNode schema;

        StubTool(String name) {
            this(name, JSON.createObjectNode());
        }

        StubTool(String name, JsonNode schema) {
            this.name = name;
            this.schema = schema;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name + " description";
        }

        @Override
        public JsonNode parametersSchema() {
            return schema;
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public ToolResult execute(JsonNode args) {
            return new ToolResult("ok", false);
        }
    }

    @Test
    void includesToolsArrayInRequest() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        JsonNode schema = JSON.readTree("{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}}}");
        provider.send(List.of(new ChatMessage(Role.USER, "hi")), List.of(new StubTool("shell", schema)),
                s -> { }, s -> { });
        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"tools\":["));
        assertTrue(body.contains("\"type\":\"function\""));
        assertTrue(body.contains("\"name\":\"shell\""));
        assertTrue(body.contains("\"description\":\"shell description\""));
        assertTrue(body.contains("\"parameters\":{\"type\":\"object\""));
    }

    @Test
    void omitsToolsArrayWhenEmpty() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        provider.send(List.of(new ChatMessage(Role.USER, "hi")), List.of(), s -> { }, s -> { });
        RecordedRequest request = server.takeRequest();
        assertFalse(request.getBody().readUtf8().contains("\"tools\""));
    }

    @Test
    void serializesAssistantToolCallsMessage() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        JsonNode args = JSON.readTree("{\"command\":\"ls\"}");
        ChatMessage assistant = new ChatMessage(Role.ASSISTANT, null, null,
                List.of(new ToolCall("call_1", "shell", args)), null);
        provider.send(List.of(assistant), List.of(), s -> { }, s -> { });
        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"tool_calls\":[{"));
        assertTrue(body.contains("\"id\":\"call_1\""));
        assertTrue(body.contains("\"function\":{\"name\":\"shell\",\"arguments\":\"{\\\"command\\\":\\\"ls\\\"}\"}"));
    }

    @Test
    void serializesToolResultMessage() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        ChatMessage toolResult = new ChatMessage(Role.TOOL, "42", null, null, "call_1");
        provider.send(List.of(toolResult), List.of(), s -> { }, s -> { });
        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"role\":\"tool\""));
        assertTrue(body.contains("\"tool_call_id\":\"call_1\""));
        assertTrue(body.contains("\"content\":\"42\""));
    }

    @Test
    void surfacesToolCallsFromSseResponse() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_9","function":{"name":"shell","arguments":"{\\"command\\":\\"ls\\"}"}}]}]}

                        data: [DONE]

                        """));
        ProviderResponse response = provider.send(List.of(new ChatMessage(Role.USER, "hi")), List.of(),
                s -> { }, s -> { });
        assertEquals(1, response.message().toolCalls().size());
        assertEquals("call_9", response.message().toolCalls().get(0).id());
        assertEquals("shell", response.message().toolCalls().get(0).name());
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=OpenAiCompatibleProviderTest`
Expected: PASS (all existing + 5 new tests)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/provider/Provider.java src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java \
        src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java
git commit -m "feat: add tools and tool_calls to provider wire format"
```

---

### Task 7: ContextBuilder tool methods

**Files:**
- Modify: `src/main/java/com/mrsmith/chat/ContextBuilder.java`, `src/main/java/com/mrsmith/chat/FullContextBuilder.java`
- Test: `src/test/java/com/mrsmith/chat/FullContextBuilderTest.java`

- [ ] **Step 1: Write the failing tests**

Read `src/test/java/com/mrsmith/chat/FullContextBuilderTest.java` first, then append:

```java
    @Test
    void appendsAssistantToolCalls() {
        FullContextBuilder builder = new FullContextBuilder();
        builder.start(null);
        builder.appendAssistantToolCalls(List.of(new ToolCall("c1", "shell", JSON.readTree("{}"))));
        ChatMessage msg = builder.messages().get(0);
        assertEquals(Role.ASSISTANT, msg.role());
        assertEquals(1, msg.toolCalls().size());
        assertEquals("c1", msg.toolCalls().get(0).id());
    }

    @Test
    void appendsToolResult() {
        FullContextBuilder builder = new FullContextBuilder();
        builder.start(null);
        builder.appendToolResult("c1", "42");
        ChatMessage msg = builder.messages().get(0);
        assertEquals(Role.TOOL, msg.role());
        assertEquals("c1", msg.toolCallId());
        assertEquals("42", msg.content());
    }
```

Add imports to the test file if not present: `com.mrsmith.provider.ToolCall`, `com.fasterxml.jackson.databind.ObjectMapper` (with a `private static final ObjectMapper JSON = new ObjectMapper();` field if the test class does not already declare one), `java.util.List`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=FullContextBuilderTest`
Expected: FAIL — `appendAssistantToolCalls`/`appendToolResult` do not exist.

- [ ] **Step 3: Modify the interface**

Replace `src/main/java/com/mrsmith/chat/ContextBuilder.java` with:

```java
package com.mrsmith.chat;

import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.ToolCall;

import java.util.List;

public interface ContextBuilder {

    void start(String systemPrompt);

    void appendUser(String content);

    void appendAssistant(String content);

    void appendAssistantToolCalls(List<ToolCall> toolCalls);

    void appendToolResult(String toolCallId, String content);

    List<ChatMessage> messages();
}
```

- [ ] **Step 4: Modify the implementation**

Replace `src/main/java/com/mrsmith/chat/FullContextBuilder.java` with:

```java
package com.mrsmith.chat;

import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Role;
import com.mrsmith.provider.ToolCall;

import java.util.ArrayList;
import java.util.List;

public class FullContextBuilder implements ContextBuilder {

    private final List<ChatMessage> context = new ArrayList<>();

    @Override
    public void start(String systemPrompt) {
        context.clear();
        if (systemPrompt != null) {
            context.add(new ChatMessage(Role.SYSTEM, systemPrompt));
        }
    }

    @Override
    public void appendUser(String content) {
        context.add(new ChatMessage(Role.USER, content));
    }

    @Override
    public void appendAssistant(String content) {
        context.add(new ChatMessage(Role.ASSISTANT, content));
    }

    @Override
    public void appendAssistantToolCalls(List<ToolCall> toolCalls) {
        context.add(new ChatMessage(Role.ASSISTANT, null, null, toolCalls, null));
    }

    @Override
    public void appendToolResult(String toolCallId, String content) {
        context.add(new ChatMessage(Role.TOOL, content, null, null, toolCallId));
    }

    @Override
    public List<ChatMessage> messages() {
        return List.copyOf(context);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=FullContextBuilderTest`
Expected: PASS (existing + 2 new)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ContextBuilder.java src/main/java/com/mrsmith/chat/FullContextBuilder.java \
        src/test/java/com/mrsmith/chat/FullContextBuilderTest.java
git commit -m "feat: support tool call and tool result in context builder"
```

---

### Task 8: Transcript tool records

**Files:**
- Modify: `src/main/java/com/mrsmith/session/TranscriptWriter.java`, `src/main/java/com/mrsmith/session/FileTranscriptWriter.java`
- Test: `src/test/java/com/mrsmith/session/FileTranscriptWriterTest.java`

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/com/mrsmith/session/FileTranscriptWriterTest.java`:

```java
    @Test
    void appendsToolCallAndToolResultRecords() throws IOException {
        FileTranscriptWriter writer = new FileTranscriptWriter(tempDir);
        UUID id = UUID.randomUUID();
        writer.start(id);
        writer.appendToolCall(id, "call_1", "shell", JSON.readTree("{\"command\":\"ls\"}"));
        writer.appendToolResult(id, "call_1", "stdout", false);

        Path file = tempDir.resolve(id.toString()).resolve("transcript.jsonl");
        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());

        JsonNode call = JSON.readTree(lines.get(0));
        assertEquals("tool_call", call.get("type").asText());
        assertEquals("call_1", call.get("id").asText());
        assertEquals("shell", call.get("name").asText());
        assertEquals("ls", call.get("arguments").get("command").asText());
        assertTrue(call.hasNonNull("timestamp"));

        JsonNode result = JSON.readTree(lines.get(1));
        assertEquals("tool_result", result.get("type").asText());
        assertEquals("call_1", result.get("id").asText());
        assertEquals("stdout", result.get("content").asText());
        assertFalse(result.get("error").asBoolean());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=FileTranscriptWriterTest`
Expected: FAIL — `appendToolCall`/`appendToolResult` do not exist.

- [ ] **Step 3: Modify the interface**

Replace `src/main/java/com/mrsmith/session/TranscriptWriter.java` with:

```java
package com.mrsmith.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.mrsmith.provider.Usage;

import java.io.IOException;
import java.util.UUID;

public interface TranscriptWriter {

    void start(UUID sessionId) throws IOException;

    void appendUser(UUID sessionId, String content) throws IOException;

    void appendAssistant(UUID sessionId, String content, String thinking,
                         Usage usage, boolean estimated) throws IOException;

    void appendToolCall(UUID sessionId, String id, String name, JsonNode arguments) throws IOException;

    void appendToolResult(UUID sessionId, String id, String content, boolean error) throws IOException;
}
```

- [ ] **Step 4: Modify the implementation**

In `src/main/java/com/mrsmith/session/FileTranscriptWriter.java`, add import `import com.fasterxml.jackson.databind.JsonNode;` and add these two methods after `appendAssistant`:

```java
    @Override
    public void appendToolCall(UUID sessionId, String id, String name, JsonNode arguments) throws IOException {
        ObjectNode record = JSON.createObjectNode();
        record.put("type", "tool_call");
        record.put("id", id);
        record.put("name", name);
        if (arguments != null) {
            record.set("arguments", arguments);
        }
        record.put("timestamp", Instant.now().toString());
        append(sessionId, record);
    }

    @Override
    public void appendToolResult(UUID sessionId, String id, String content, boolean error) throws IOException {
        ObjectNode record = JSON.createObjectNode();
        record.put("type", "tool_result");
        record.put("id", id);
        record.put("content", content);
        record.put("error", error);
        record.put("timestamp", Instant.now().toString());
        append(sessionId, record);
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=FileTranscriptWriterTest`
Expected: PASS (existing + 1 new)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/session/TranscriptWriter.java src/main/java/com/mrsmith/session/FileTranscriptWriter.java \
        src/test/java/com/mrsmith/session/FileTranscriptWriterTest.java
git commit -m "feat: record tool call and tool result in transcripts"
```

---

### Task 9: Config — per-agent tools

**Files:**
- Modify: `src/main/java/com/mrsmith/config/AgentConfig.java`, `AppConfig.java`, `ConfigLoader.java`, `AgentCatalog.java`
- Test: `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`, `src/test/java/com/mrsmith/config/AgentCatalogTest.java`

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/com/mrsmith/config/AgentCatalogTest.java` (note: `List` is already imported):

```java
    @Test
    void resolveCarriesToolNames() {
        AgentConfig withTools = new AgentConfig("coder", "opencode", "model-x", null, null, List.of("shell", "read_file"));
        AgentCatalog catalog = new AgentCatalog(List.of(provider), List.of(withTools), "coder", true, Path.of("/tmp/s"));
        assertEquals(List.of("shell", "read_file"), catalog.resolve("coder").tools());
    }

    @Test
    void emptyToolsByDefault() {
        AgentCatalog catalog = new AgentCatalog(List.of(provider), List.of(agent), "coder", true, Path.of("/tmp/s"));
        assertEquals(List.of(), catalog.resolve("coder").tools());
    }

    @Test
    void unknownToolNameThrows() {
        AgentConfig bad = new AgentConfig("coder", "opencode", "model-x", null, null, List.of("nope"));
        assertThrows(ConfigException.class,
                () -> new AgentCatalog(List.of(provider), List.of(bad), "coder", true, Path.of("/tmp/s")));
    }
```

Append to `src/test/java/com/mrsmith/config/ConfigLoaderTest.java` (add `import java.util.List;`):

```java
    @Test
    void parsesPerAgentTools() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m", "tools": ["shell", "read_file"] } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(List.of("shell", "read_file"), catalog.resolve("a").tools());
    }

    @Test
    void toolsDefaultToEmpty() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals(List.of(), catalog.resolve("a").tools());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=AgentCatalogTest,ConfigLoaderTest`
Expected: FAIL — `AgentConfig` has no 6-arg constructor with tools; `AppConfig.tools()` does not exist.

- [ ] **Step 3: Modify AgentConfig**

Replace `src/main/java/com/mrsmith/config/AgentConfig.java` with:

```java
package com.mrsmith.config;

import java.util.List;

public record AgentConfig(String name, String provider, String model,
                          String systemPrompt, Integer maxContextTokens, List<String> tools) {

    public AgentConfig(String name, String provider, String model,
                       String systemPrompt, Integer maxContextTokens) {
        this(name, provider, model, systemPrompt, maxContextTokens, List.of());
    }
}
```

- [ ] **Step 4: Modify AppConfig**

Replace `src/main/java/com/mrsmith/config/AppConfig.java` with:

```java
package com.mrsmith.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record AppConfig(String apiKey, String baseUrl, String model, String systemPrompt,
                        Integer maxContextTokens, boolean includeUsage, Path sessionsDir,
                        List<String> tools) {

    public AppConfig {
        Objects.requireNonNull(apiKey, "apiKey is required");
        Objects.requireNonNull(baseUrl, "baseUrl is required");
        Objects.requireNonNull(model, "model is required");
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public AppConfig(String apiKey, String baseUrl, String model, String systemPrompt) {
        this(apiKey, baseUrl, model, systemPrompt, null, true, null, List.of());
    }

    public AppConfig(String apiKey, String baseUrl, String model, String systemPrompt,
                     Integer maxContextTokens, boolean includeUsage) {
        this(apiKey, baseUrl, model, systemPrompt, maxContextTokens, includeUsage, null, List.of());
    }

    public AppConfig(String apiKey, String baseUrl, String model, String systemPrompt,
                     Integer maxContextTokens, boolean includeUsage, Path sessionsDir) {
        this(apiKey, baseUrl, model, systemPrompt, maxContextTokens, includeUsage, sessionsDir, List.of());
    }
}
```

- [ ] **Step 5: Modify ConfigLoader.parseAgents**

In `src/main/java/com/mrsmith/config/ConfigLoader.java`, replace `parseAgents` (lines 67-81) with:

```java
    private static List<AgentConfig> parseAgents(JsonNode root) {
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
                        parseTools(node)));
            }
        }
        return result;
    }

    private static List<String> parseTools(JsonNode agentNode) {
        List<String> tools = new ArrayList<>();
        JsonNode arr = agentNode.path("tools");
        if (arr.isArray()) {
            for (JsonNode tool : arr) {
                tools.add(tool.asText());
            }
        }
        return tools;
    }
```

- [ ] **Step 6: Modify AgentCatalog**

In `src/main/java/com/mrsmith/config/AgentCatalog.java`:

1. Add import: `import com.mrsmith.tool.ToolRegistry;`.

2. In the agent validation loop (after the `agent.model()` check, currently around line 41), add:

```java
            for (String tool : agent.tools()) {
                if (!ToolRegistry.builtinNames().contains(tool)) {
                    throw new ConfigException("Agent '" + agent.name() + "' references unknown tool '" + tool + "'");
                }
            }
```

3. Change `resolve` (currently lines 60-61) from:

```java
        return new AppConfig(provider.apiKey(), provider.baseUrl(), agent.model(),
                agent.systemPrompt(), agent.maxContextTokens(), includeUsage, sessionsDir);
```

to:

```java
        return new AppConfig(provider.apiKey(), provider.baseUrl(), agent.model(),
                agent.systemPrompt(), agent.maxContextTokens(), includeUsage, sessionsDir, agent.tools());
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn test -Dtest=AgentCatalogTest,ConfigLoaderTest`
Expected: PASS (existing + 5 new)

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/mrsmith/config/AgentConfig.java src/main/java/com/mrsmith/config/AppConfig.java \
        src/main/java/com/mrsmith/config/ConfigLoader.java src/main/java/com/mrsmith/config/AgentCatalog.java \
        src/test/java/com/mrsmith/config/AgentCatalogTest.java src/test/java/com/mrsmith/config/ConfigLoaderTest.java
git commit -m "feat: add per-agent tool configuration"
```

---

### Task 10: ChatSession tool loop + wiring

**Files:**
- Create: `src/main/java/com/mrsmith/tool/ToolRegistryFactory.java`
- Modify: `src/main/java/com/mrsmith/chat/ChatSession.java`, `src/main/java/com/mrsmith/cli/ChatCommand.java`
- Test: `src/test/java/com/mrsmith/chat/ChatSessionTest.java`

- [ ] **Step 1: Write the failing tests**

In `src/test/java/com/mrsmith/chat/ChatSessionTest.java`, first add imports:

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrsmith.provider.ToolCall;
import com.mrsmith.tool.Tool;
import com.mrsmith.tool.ToolRegistry;
import com.mrsmith.tool.ToolRegistryFactory;
import com.mrsmith.tool.ToolResult;
```

Add a static JSON mapper field next to the other class members:

```java
    private static final ObjectMapper JSON = new ObjectMapper();
```

Add these test methods:

```java
    @Test
    void runsToolLoopAndFeedsResultBack() throws Exception {
        FakeTool readFile = new FakeTool("read_file", true, new ToolResult("file contents", false));
        ToolRegistryFactory registryFactory = config -> new ToolRegistry(List.of(readFile));
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_1", "read_file", JSON.readTree("{\"path\":\"a.txt\"}")),
                "final answer");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, "a");
        session.run();
        assertEquals(2, toolProvider.calls);
        assertEquals(1, readFile.calls);
        List<ChatMessage> secondSend = toolProvider.receivedHistories.get(1);
        assertEquals(Role.TOOL, secondSend.get(secondSend.size() - 1).role());
        assertEquals("file contents", secondSend.get(secondSend.size() - 1).content());
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("tool: read_file(a.txt) -> ok")));
        assertEquals(1, transcripts.toolCallIds.size());
        assertEquals("call_1", transcripts.toolCallIds.get(0));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("final answer")));
    }

    @Test
    void declinesNonReadOnlyTool() throws Exception {
        FakeTool shell = new FakeTool("shell", false, new ToolResult("ran", false));
        ToolRegistryFactory registryFactory = config -> new ToolRegistry(List.of(shell));
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_2", "shell", JSON.readTree("{\"command\":\"rm -rf /\"}")),
                "answer after decline");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "n", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, "a");
        session.run();
        assertEquals(0, shell.calls);
        List<ChatMessage> secondSend = toolProvider.receivedHistories.get(1);
        ChatMessage last = secondSend.get(secondSend.size() - 1);
        assertEquals(Role.TOOL, last.role());
        assertTrue(last.content().contains("declined"));
    }

    @Test
    void confirmsNonReadOnlyToolOnYes() throws Exception {
        FakeTool shell = new FakeTool("shell", false, new ToolResult("ran", false));
        ToolRegistryFactory registryFactory = config -> new ToolRegistry(List.of(shell));
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_3", "shell", JSON.readTree("{\"command\":\"echo hi\"}")),
                "answer");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "y", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, "a");
        session.run();
        assertEquals(1, shell.calls);
    }

    @Test
    void unknownToolProducesErrorResult() throws Exception {
        ToolRegistryFactory registryFactory = config -> new ToolRegistry(List.of());
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_4", "nonexistent", JSON.readTree("{}")),
                "answer");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, "a");
        session.run();
        List<ChatMessage> secondSend = toolProvider.receivedHistories.get(1);
        ChatMessage last = secondSend.get(secondSend.size() - 1);
        assertEquals(Role.TOOL, last.role());
        assertTrue(last.content().contains("Unknown tool: nonexistent"));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("tool: nonexistent() -> error")));
    }

    @Test
    void stopsAtToolRoundLimit() throws Exception {
        FakeTool tool = new FakeTool("read_file", true, new ToolResult("data", false));
        ToolRegistryFactory registryFactory = config -> new ToolRegistry(List.of(tool));
        FakeToolProvider provider = new FakeToolProvider();
        provider.alwaysCall("read_file", JSON.readTree("{\"path\":\"a.txt\"}"));
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(provider), registryFactory, "a");
        session.run();
        assertEquals(10, provider.calls);
        List<ChatMessage> lastSend = provider.receivedHistories.get(provider.receivedHistories.size() - 1);
        ChatMessage last = lastSend.get(lastSend.size() - 1);
        assertEquals(Role.TOOL, last.role());
        assertTrue(last.content().contains("round limit"));
    }

    @Test
    void noToolsAgentSendsEmptyToolsList() throws Exception {
        FakeProvider provider = new FakeProvider();
        ToolRegistryFactory registryFactory = config -> new ToolRegistry(List.of());
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(provider), registryFactory, "a");
        session.run();
        assertTrue(provider.receivedTools.get(0).isEmpty());
    }
```

Update the existing `FakeProvider` class (currently `send(List<ChatMessage>, Consumer, Consumer)`) to the new signature and record received tools:

```java
    static class FakeProvider implements Provider {
        final Usage turnUsage;
        final boolean estimated;
        final String thinking;
        final List<List<ChatMessage>> receivedHistories = new ArrayList<>();
        final List<List<Tool>> receivedTools = new ArrayList<>();
        int calls = 0;

        FakeProvider() {
            this(new Usage(0, 0), false, null);
        }

        FakeProvider(Usage turnUsage, boolean estimated) {
            this(turnUsage, estimated, null);
        }

        FakeProvider(Usage turnUsage, boolean estimated, String thinking) {
            this.turnUsage = turnUsage;
            this.estimated = estimated;
            this.thinking = thinking;
        }

        @Override
        public ProviderResponse send(List<ChatMessage> history, List<Tool> tools,
                                     Consumer<String> tokenSink, Consumer<String> reasoningSink) {
            receivedHistories.add(new ArrayList<>(history));
            receivedTools.add(new ArrayList<>(tools));
            calls++;
            ChatMessage last = history.get(history.size() - 1);
            String reply = last.content() + " response";
            tokenSink.accept(reply);
            if (thinking != null) {
                reasoningSink.accept(thinking);
            }
            return new ProviderResponse(new ChatMessage(Role.ASSISTANT, reply, thinking), turnUsage, estimated);
        }
    }
```

Update the inline `Provider` lambdas that appear in existing tests to the 4-arg signature (they are at the lines defining `Provider failing = ...` and `Provider interrupted = ...`):

```java
        Provider failing = (history, tools, sink, reasoningSink) -> {
            throw new ProviderException("HTTP 401: bad key");
        };
```

```java
        Provider interrupted = (history, tools, sink, reasoningSink) -> {
            sink.accept("partial");
            throw new ProviderException("Stream interrupted", null, "partial");
        };
```

```java
        Provider failing = (history, tools, sink, reasoningSink) -> {
            throw new IllegalStateException("boom");
        };
```

```java
        Provider interrupted = (history, tools, sink, reasoningSink) -> {
            reasoningSink.accept("half");
            throw new ProviderException("Stream interrupted", null, null, "half");
        };
```

Update `FirstThenProvider.send` to the new signature:

```java
        @Override
        public ProviderResponse send(List<ChatMessage> history, List<Tool> tools,
                                     Consumer<String> tokenSink, Consumer<String> reasoningSink) {
            if (calls++ == 0) {
                return first.send(history, tools, tokenSink, reasoningSink);
            }
            return then.send(history, tools, tokenSink, reasoningSink);
        }
```

Add tool-call recording to `FakeTranscriptWriter`:

```java
        final List<String> toolCallIds = new ArrayList<>();
        final List<String> toolResultIds = new ArrayList<>();
        final List<String> toolResultContents = new ArrayList<>();

        @Override
        public void appendToolCall(UUID sessionId, String id, String name, JsonNode arguments) throws IOException {
            appendAttempts++;
            if (failAppend) {
                throw new IOException("boom");
            }
            toolCallIds.add(id);
        }

        @Override
        public void appendToolResult(UUID sessionId, String id, String content, boolean error) throws IOException {
            appendAttempts++;
            if (failAppend) {
                throw new IOException("boom");
            }
            toolResultIds.add(id);
            toolResultContents.add(content);
        }
```

Add the new helper classes (FakeToolProvider and FakeTool) at the end of the class body:

```java
    static class FakeTool implements Tool {
        final String name;
        final boolean readOnly;
        final ToolResult result;
        int calls = 0;

        FakeTool(String name, boolean readOnly, ToolResult result) {
            this.name = name;
            this.readOnly = readOnly;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public JsonNode parametersSchema() {
            return JSON.createObjectNode();
        }

        @Override
        public boolean isReadOnly() {
            return readOnly;
        }

        @Override
        public ToolResult execute(JsonNode args) {
            calls++;
            return result;
        }
    }

    static class FakeToolProvider extends FakeProvider {
        private ToolCall firstCall;
        private boolean alwaysCall;
        private String answer;

        FakeToolProvider(ToolCall firstCall, String answer) {
            this.firstCall = firstCall;
            this.answer = answer;
        }

        FakeToolProvider() {
        }

        void alwaysCall(String name, JsonNode args) {
            this.firstCall = new ToolCall("call_x", name, args);
            this.alwaysCall = true;
        }

        @Override
        public ProviderResponse send(List<ChatMessage> history, List<Tool> tools,
                                     Consumer<String> tokenSink, Consumer<String> reasoningSink) {
            receivedHistories.add(new ArrayList<>(history));
            receivedTools.add(new ArrayList<>(tools));
            calls++;
            if (alwaysCall && calls < 10) {
                return new ProviderResponse(
                        new ChatMessage(Role.ASSISTANT, null, null, List.of(firstCall), null),
                        turnUsage, estimated);
            }
            if (firstCall != null && calls == 1) {
                return new ProviderResponse(
                        new ChatMessage(Role.ASSISTANT, null, null, List.of(firstCall), null),
                        turnUsage, estimated);
            }
            ChatMessage last = history.get(history.size() - 1);
            String reply = answer != null ? answer : last.content() + " response";
            tokenSink.accept(reply);
            if (thinking != null) {
                reasoningSink.accept(thinking);
            }
            return new ProviderResponse(new ChatMessage(Role.ASSISTANT, reply, thinking), turnUsage, estimated);
        }
    }
```

Add a `noToolsFactory()` helper:

```java
    private ToolRegistryFactory noToolsFactory() {
        return config -> new ToolRegistry(List.of());
    }
```

Update the `session(...)` helper (currently 6-arg ChatSession construction) to insert `noToolsFactory()` before `"a"`:

```java
    private ChatSession session(Provider provider, StubIo io, FakeTranscriptWriter transcripts,
                                AgentCatalog catalog) {
        return new ChatSession(io, transcripts, new FullContextBuilder(), catalog,
                new FakeProviderFactory(provider), noToolsFactory(), "a");
    }
```

Update the three direct `new ChatSession(...)` constructions in the tests `agentSwitchRebuildsProviderAndStartsNewSession`, `unknownAgentSwitchIsRejected`, and `agentsCommandListsNames` to insert `noToolsFactory()` before the initial agent name argument.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=ChatSessionTest`
Expected: FAIL — `ChatSession` has no 7-arg constructor; `Provider` signature mismatch in the lambdas.

- [ ] **Step 3: Create ToolRegistryFactory**

Create `src/main/java/com/mrsmith/tool/ToolRegistryFactory.java`:

```java
package com.mrsmith.tool;

import com.mrsmith.config.AppConfig;

public interface ToolRegistryFactory {

    ToolRegistry create(AppConfig config);
}
```

- [ ] **Step 4: Modify ChatSession**

Replace the entire contents of `src/main/java/com/mrsmith/chat/ChatSession.java` with:

```java
package com.mrsmith.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.mrsmith.config.AgentCatalog;
import com.mrsmith.config.AppConfig;
import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderException;
import com.mrsmith.provider.ProviderFactory;
import com.mrsmith.provider.ProviderResponse;
import com.mrsmith.provider.Role;
import com.mrsmith.provider.ToolCall;
import com.mrsmith.provider.Usage;
import com.mrsmith.session.TranscriptWriter;
import com.mrsmith.tool.Tool;
import com.mrsmith.tool.ToolException;
import com.mrsmith.tool.ToolRegistry;
import com.mrsmith.tool.ToolRegistryFactory;
import com.mrsmith.tool.ToolResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public class ChatSession {

    private static final int WARN_THRESHOLD_PERCENT = 85;
    private static final int LIMIT_PERCENT = 100;
    private static final int MAX_TOOL_ROUNDS = 8;

    private final IO io;
    private final TranscriptWriter transcripts;
    private final ContextBuilder contextBuilder;
    private final AgentCatalog agents;
    private final ProviderFactory providerFactory;
    private final ToolRegistryFactory toolRegistryFactory;
    private final String initialAgentName;

    private final List<ChatMessage> history = new ArrayList<>();
    private final UsageTracker tracker = new UsageTracker();
    private boolean warned85;
    private boolean warned100;
    private UUID currentSessionId;
    private String currentAgentName;
    private AppConfig config;
    private Provider provider;
    private ToolRegistry toolRegistry;

    public ChatSession(IO io, TranscriptWriter transcripts, ContextBuilder contextBuilder,
                       AgentCatalog agents, ProviderFactory providerFactory,
                       ToolRegistryFactory toolRegistryFactory, String initialAgentName) {
        this.io = io;
        this.transcripts = transcripts;
        this.contextBuilder = contextBuilder;
        this.agents = agents;
        this.providerFactory = providerFactory;
        this.toolRegistryFactory = toolRegistryFactory;
        this.initialAgentName = initialAgentName;
    }

    public void run() throws IOException {
        io.writeLine("Mr Smith. Type /help for commands, /exit to quit.");
        currentAgentName = initialAgentName;
        applyAgent();
        io.writeLine("Agent: " + currentAgentName);
        startFreshSession();
        String line;
        while ((line = io.readLine()) != null) {
            if (line.equals("/exit")) {
                break;
            }
            if (handleCommand(line)) {
                continue;
            }
            history.add(new ChatMessage(Role.USER, line));
            appendUser(line);
            contextBuilder.appendUser(line);
            try {
                TurnResult turn = runToolLoop();
                history.add(turn.message());
                contextBuilder.appendAssistant(turn.message().content());
                appendAssistant(turn.message().content(), turn.message().thinking(),
                        turn.usage(), turn.estimated());
                io.writeLine("");
                tracker.recordTurn(turn.usage(), turn.estimated());
                String usageLine = tracker.lastTurnLine();
                if (!usageLine.isEmpty()) {
                    io.writeLine(usageLine);
                }
                warnIfNearLimit();
            } catch (ProviderException e) {
                if (e.hasPartialContent() || e.partialThinking() != null) {
                    history.add(new ChatMessage(Role.ASSISTANT, e.partialContent(), e.partialThinking()));
                    contextBuilder.appendAssistant(e.partialContent());
                    appendAssistant(e.partialContent(), e.partialThinking(), null, false);
                }
                io.writeLine("");
                io.writeLine("Error: " + e.getMessage());
            } catch (RuntimeException e) {
                io.writeLine("");
                io.writeLine("Error: " + e.getMessage());
            }
        }
    }

    private TurnResult runToolLoop() {
        int prompt = 0;
        int completion = 0;
        boolean estimated = false;
        for (int round = 0; ; round++) {
            List<ChatMessage> context = contextBuilder.messages();
            ProviderResponse response = provider.send(context, toolRegistry.tools(),
                    io::write, io::writeReasoning);
            prompt += tokens(response.usage().promptTokens());
            completion += tokens(response.usage().completionTokens());
            estimated = estimated || response.usageEstimated();
            ChatMessage message = response.message();
            List<ToolCall> calls = message.toolCalls();
            if (calls == null || calls.isEmpty()) {
                return new TurnResult(message, new Usage(prompt, completion), estimated);
            }
            if (round >= MAX_TOOL_ROUNDS) {
                recordToolCallMessage(message, calls);
                String limitContent = "Tool round limit (" + MAX_TOOL_ROUNDS + ") reached; answer without more tool calls.";
                ChatMessage limit = new ChatMessage(Role.TOOL, limitContent, null, null, "__limit__");
                history.add(limit);
                contextBuilder.appendToolResult(limit.toolCallId(), limit.content());
                appendToolResult("__limit__", limitContent, false);
                response = provider.send(contextBuilder.messages(), toolRegistry.tools(),
                        io::write, io::writeReasoning);
                prompt += tokens(response.usage().promptTokens());
                completion += tokens(response.usage().completionTokens());
                estimated = estimated || response.usageEstimated();
                return new TurnResult(response.message(), new Usage(prompt, completion), estimated);
            }
            recordToolCallMessage(message, calls);
            for (ToolCall call : calls) {
                ToolResult result = executeTool(call);
                io.writeLine(statusLine(call, result));
                ChatMessage toolMessage = new ChatMessage(Role.TOOL, result.content(), null, null, call.id());
                history.add(toolMessage);
                contextBuilder.appendToolResult(call.id(), result.content());
                appendToolResult(call.id(), result.content(), result.error());
            }
        }
    }

    private int tokens(Integer value) {
        return value == null ? 0 : value;
    }

    private void recordToolCallMessage(ChatMessage message, List<ToolCall> calls) {
        history.add(message);
        contextBuilder.appendAssistantToolCalls(calls);
        for (ToolCall call : calls) {
            appendToolCall(call);
        }
    }

    private ToolResult executeTool(ToolCall call) {
        Optional<Tool> found = toolRegistry.find(call.name());
        if (found.isEmpty()) {
            return new ToolResult("Unknown tool: " + call.name(), true);
        }
        Tool tool = found.get();
        if (!tool.isReadOnly() && !confirm(call, tool)) {
            return new ToolResult("User declined to run " + call.name() + ".", true);
        }
        try {
            return tool.execute(call.arguments());
        } catch (ToolException e) {
            return new ToolResult(e.getMessage(), true);
        }
    }

    private boolean confirm(ToolCall call, Tool tool) {
        io.write("Run " + tool.name() + "(" + describe(call) + ") [y/N]? ");
        String answer;
        try {
            answer = io.readLine();
        } catch (IOException e) {
            return false;
        }
        return answer != null && (answer.trim().equalsIgnoreCase("y")
                || answer.trim().equalsIgnoreCase("yes"));
    }

    private String statusLine(ToolCall call, ToolResult result) {
        return "tool: " + call.name() + "(" + describe(call) + ") -> "
                + (result.error() ? "error" : "ok");
    }

    private String describe(ToolCall call) {
        JsonNode args = call.arguments();
        for (String key : List.of("command", "path", "pattern", "url")) {
            JsonNode value = args != null ? args.get(key) : null;
            if (value != null && value.isTextual()) {
                return value.asText();
            }
        }
        return "";
    }

    private void applyAgent() {
        config = agents.resolve(currentAgentName);
        provider = providerFactory.create(config);
        toolRegistry = toolRegistryFactory.create(config);
    }

    private void startFreshSession() {
        history.clear();
        tracker.reset();
        warned85 = false;
        warned100 = false;
        contextBuilder.start(config.systemPrompt());
        startNewSession();
    }

    private void startNewSession() {
        UUID id = UuidV7.random();
        try {
            transcripts.start(id);
            currentSessionId = id;
        } catch (IOException e) {
            currentSessionId = null;
            System.err.println("Warning: could not create session folder for " + id
                    + ": " + e.getMessage() + ". Session transcript disabled.");
            return;
        }
        io.writeLine("Session: " + id);
    }

    private void switchAgent(String name) {
        if (!agents.agentNames().contains(name)) {
            io.writeLine("Unknown agent: " + name);
            return;
        }
        currentAgentName = name;
        applyAgent();
        io.writeLine("Agent: " + name);
        startFreshSession();
    }

    private void appendUser(String content) {
        if (currentSessionId == null) {
            return;
        }
        try {
            transcripts.appendUser(currentSessionId, content);
        } catch (IOException e) {
            System.err.println("Warning: could not write session transcript: " + e.getMessage());
            currentSessionId = null;
        }
    }

    private void appendAssistant(String content, String thinking, Usage usage, boolean estimated) {
        if (currentSessionId == null) {
            return;
        }
        try {
            transcripts.appendAssistant(currentSessionId, content, thinking, usage, estimated);
        } catch (IOException e) {
            System.err.println("Warning: could not write session transcript: " + e.getMessage());
            currentSessionId = null;
        }
    }

    private void appendToolCall(ToolCall call) {
        if (currentSessionId == null) {
            return;
        }
        try {
            transcripts.appendToolCall(currentSessionId, call.id(), call.name(), call.arguments());
        } catch (IOException e) {
            System.err.println("Warning: could not write session transcript: " + e.getMessage());
            currentSessionId = null;
        }
    }

    private void appendToolResult(String id, String content, boolean error) {
        if (currentSessionId == null) {
            return;
        }
        try {
            transcripts.appendToolResult(currentSessionId, id, content, error);
        } catch (IOException e) {
            System.err.println("Warning: could not write session transcript: " + e.getMessage());
            currentSessionId = null;
        }
    }

    private void warnIfNearLimit() {
        if (!contextLimitConfigured()) {
            return;
        }
        int pct = pctOfMax();
        if (pct >= LIMIT_PERCENT) {
            if (!warned100) {
                warned100 = true;
                io.writeLine(String.format(Locale.US,
                        "Warning: session reached 100%% of your configured %,d-token context limit — consider /reset",
                        config.maxContextTokens()));
            }
        } else if (pct >= WARN_THRESHOLD_PERCENT) {
            if (!warned85) {
                warned85 = true;
                io.writeLine(String.format(Locale.US,
                        "Warning: session at %d%% of your configured %,d-token context limit — consider /reset",
                        pct, config.maxContextTokens()));
            }
        }
    }

    private boolean contextLimitConfigured() {
        Integer maxContext = config.maxContextTokens();
        return maxContext != null && maxContext > 0;
    }

    private int pctOfMax() {
        return (int) Math.round(tracker.totalTokens() * 100.0 / config.maxContextTokens());
    }

    private boolean handleCommand(String line) {
        if (!line.startsWith("/")) {
            return false;
        }
        if (line.startsWith("/agent ")) {
            switchAgent(line.substring("/agent ".length()).trim());
            return true;
        }
        switch (line) {
            case "/reset" -> {
                startFreshSession();
                io.writeLine("History cleared.");
            }
            case "/agents" -> io.writeLine("Agents: " + String.join(", ", agents.agentNames()));
            case "/usage" -> io.writeLine(usageReport());
            case "/help" -> io.writeLine("Commands: /exit, /reset, /help, /usage, /agents, /agent <name>. Anything else is sent to the LLM.");
            default -> io.writeLine("Unknown command: " + line + " (type /help)");
        }
        return true;
    }

    private String usageReport() {
        StringBuilder report = new StringBuilder(tracker.usageReport());
        if (contextLimitConfigured()) {
            report.append(String.format(Locale.US, "%n  context limit: %,d configured (%d%% used)",
                    config.maxContextTokens(), pctOfMax()));
        }
        report.append(String.format(Locale.US, "%n  history: %d messages", history.size()));
        return report.toString();
    }

    private record TurnResult(ChatMessage message, Usage usage, boolean estimated) {
    }
}
```

- [ ] **Step 5: Modify ChatCommand**

In `src/main/java/com/mrsmith/cli/ChatCommand.java`, add import `import com.mrsmith.tool.ToolRegistry;` and change the ChatSession construction (currently lines 52-53) to:

```java
        ChatSession session = new ChatSession(io, transcripts, contextBuilder, catalog,
                OpenAiCompatibleProvider::new, config -> ToolRegistry.with(config.tools()), initialAgent);
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn test -Dtest=ChatSessionTest`
Expected: PASS (all existing + 6 new tests)

- [ ] **Step 7: Run the full suite**

Run: `mvn test`
Expected: PASS — all tests compile and pass (118 baseline + all new tests across the feature).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/mrsmith/tool/ToolRegistryFactory.java src/main/java/com/mrsmith/chat/ChatSession.java \
        src/main/java/com/mrsmith/cli/ChatCommand.java src/test/java/com/mrsmith/chat/ChatSessionTest.java
git commit -m "feat: add tool execution loop to chat session"
```

---

### Task 11: Integration verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite**

Run: `mvn test`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 2: Build the jar**

Run: `mvn -q package`
Expected: `target/mr-smith.jar` produced.

- [ ] **Step 3: Manual smoke test**

Run `java -jar target/mr-smith.jar --help` — expected to show `--agent` and `--sessions-dir` only (unchanged CLI).

Verify config validation: run `java -jar target/mr-smith.jar` against a config whose agent lists an unknown tool; expect a clean error message and exit code 1 (no stack trace).

- [ ] **Step 4: Commit (only if any files changed during verification)**

If verification required no code changes, no commit is needed. Otherwise commit the fixes with a descriptive message.
