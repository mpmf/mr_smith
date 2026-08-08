# Agentic Tools (edit, todowrite, question) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three always-available built-in tools to Mr Smith that mirror the host harness tools by name and behavior: `edit` (exact substring replacement in a file), `todowrite` (session task list), and `question` (prompt the user and return the answer).

**Architecture:** Add `EditTool`, `TodowriteTool`, and `QuestionTool` to `com.mrsmith.tool`. `ToolRegistry.with` auto-adds all three (plus `SkillTool` when skills exist); the registry factory gains an `IO` parameter so `QuestionTool` can prompt. `TodowriteTool` holds the session task list and implements `Resettable` (cleared on `/reset` and `/agent`). `ChatSession` gains a `/tasks` command. `edit` requires approval; `todowrite`/`question` run without prompting.

**Tech Stack:** Java 21, JUnit 5 (Jupiter), Jackson (JsonNode for tool args/schemas), Maven, picocli.

---

## File Structure

**Create (main):**
- `src/main/java/com/mrsmith/tool/EditTool.java`
- `src/main/java/com/mrsmith/tool/TodowriteTool.java`
- `src/main/java/com/mrsmith/tool/QuestionTool.java`

**Modify (main):**
- `src/main/java/com/mrsmith/tool/ToolRegistry.java` — `with(..., IO)`; auto-add the three tools
- `src/main/java/com/mrsmith/tool/ToolRegistryFactory.java` — `create(config, catalog, io)`
- `src/main/java/com/mrsmith/cli/ChatCommand.java` — factory lambda gains `io`
- `src/main/java/com/mrsmith/chat/ChatSession.java` — `applyAgent` passes `io`; `/tasks` command; `describe` shows `filePath`

**Create (test):**
- `src/test/java/com/mrsmith/tool/EditToolTest.java`
- `src/test/java/com/mrsmith/tool/TodowriteToolTest.java`
- `src/test/java/com/mrsmith/tool/QuestionToolTest.java`

**Modify (test):**
- `src/test/java/com/mrsmith/tool/ToolRegistryTest.java`
- `src/test/java/com/mrsmith/chat/ChatSessionTest.java`

---

### Task 1: EditTool

**Files:**
- Create: `src/main/java/com/mrsmith/tool/EditTool.java`
- Create: `src/test/java/com/mrsmith/tool/EditToolTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mrsmith/tool/EditToolTest.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private EditTool tool() {
        return new EditTool(tempDir);
    }

    @Test
    void replacesSingleMatch() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "hello world");
        ToolResult result = tool().execute(JSON.readTree(
                "{\"filePath\":\"a.txt\",\"oldString\":\"world\",\"newString\":\"there\"}"));
        assertFalse(result.error());
        assertEquals("Edited a.txt (1 replacements)", result.content());
        assertEquals("hello there", Files.readString(file));
    }

    @Test
    void noMatchIsErrorAndFileUnchanged() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "hello world");
        ToolResult result = tool().execute(JSON.readTree(
                "{\"filePath\":\"a.txt\",\"oldString\":\"xyz\",\"newString\":\"abc\"}"));
        assertTrue(result.error());
        assertTrue(result.content().contains("oldString not found"));
        assertEquals("hello world", Files.readString(file));
    }

    @Test
    void multipleMatchesWithoutReplaceAllIsError() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "foo foo");
        ToolResult result = tool().execute(JSON.readTree(
                "{\"filePath\":\"a.txt\",\"oldString\":\"foo\",\"newString\":\"bar\"}"));
        assertTrue(result.error());
        assertTrue(result.content().contains("found 2 matches"));
        assertEquals("foo foo", Files.readString(file));
    }

    @Test
    void replaceAllReplacesEveryOccurrence() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "foo foo");
        ToolResult result = tool().execute(JSON.readTree(
                "{\"filePath\":\"a.txt\",\"oldString\":\"foo\",\"newString\":\"bar\",\"replaceAll\":true}"));
        assertFalse(result.error());
        assertEquals("Edited a.txt (2 replacements)", result.content());
        assertEquals("bar bar", Files.readString(file));
    }

    @Test
    void newStringEqualToOldStringIsError() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "foo");
        ToolResult result = tool().execute(JSON.readTree(
                "{\"filePath\":\"a.txt\",\"oldString\":\"foo\",\"newString\":\"foo\"}"));
        assertTrue(result.error());
        assertEquals("newString must differ from oldString", result.content());
        assertEquals("foo", Files.readString(file));
    }

    @Test
    void missingFileIsError() {
        ToolResult result = tool().execute(JSON.readTree(
                "{\"filePath\":\"nope.txt\",\"oldString\":\"x\",\"newString\":\"y\"}"));
        assertTrue(result.error());
        assertTrue(result.content().contains("file not found"));
    }

    @Test
    void pathEscapingWorkingDirectoryIsError() {
        ToolResult result = tool().execute(JSON.readTree(
                "{\"filePath\":\"../escape.txt\",\"oldString\":\"x\",\"newString\":\"y\"}"));
        assertTrue(result.error());
        assertTrue(result.content().contains("escapes"));
    }

    @Test
    void missingArgumentsThrow() {
        assertThrows(ToolException.class, () -> tool().execute(JSON.readTree("{}")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=EditToolTest test`
Expected: BUILD FAILURE — `cannot find symbol: class EditTool`

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/mrsmith/tool/EditTool.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class EditTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_BYTES = 1_048_576;

    private final Path root;

    public EditTool() {
        this(Path.of("").toAbsolutePath());
    }

    public EditTool(Path root) {
        this.root = root;
    }

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String description() {
        return "Replace an exact substring in a file. Fails unless oldString occurs exactly once (or replaceAll is true).";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("filePath").put("type", "string");
        properties.putObject("oldString").put("type", "string");
        properties.putObject("newString").put("type", "string");
        properties.putObject("replaceAll").put("type", "boolean");
        schema.putArray("required").add("filePath").add("oldString").add("newString");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String pathArg = args.path("filePath").asText(null);
        if (pathArg == null || pathArg.isBlank()) {
            throw new ToolException("missing required 'filePath' argument");
        }
        String oldString = args.path("oldString").asText(null);
        if (oldString == null || oldString.isBlank()) {
            throw new ToolException("missing required 'oldString' argument");
        }
        String newString = args.path("newString").asText(null);
        if (newString == null) {
            throw new ToolException("missing required 'newString' argument");
        }
        if (newString.equals(oldString)) {
            return new ToolResult("newString must differ from oldString", true);
        }
        boolean replaceAll = args.path("replaceAll").asBoolean(false);
        try {
            Path target = ToolPaths.requireWithin(root, pathArg);
            if (!Files.isRegularFile(target)) {
                return new ToolResult("file not found: " + pathArg, true);
            }
            Path real = ToolPaths.requireCanonicalWithin(root, target);
            byte[] bytes = Files.readAllBytes(real);
            if (bytes.length > MAX_BYTES) {
                return new ToolResult("file too large to edit (max " + MAX_BYTES + " bytes)", true);
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            int count = countOccurrences(content, oldString);
            if (count == 0) {
                return new ToolResult("oldString not found in file", true);
            }
            if (count > 1 && !replaceAll) {
                return new ToolResult("found " + count
                        + " matches; set replaceAll=true or provide a more specific oldString", true);
            }
            String updated = count == 1
                    ? replaceFirst(content, oldString, newString)
                    : content.replace(oldString, newString);
            Files.writeString(real, updated, StandardCharsets.UTF_8);
            return new ToolResult("Edited " + root.relativize(target) + " (" + count + " replacements)", false);
        } catch (ToolException e) {
            return new ToolResult(e.getMessage(), true);
        } catch (IOException e) {
            throw new ToolException("could not edit file: " + e.getMessage(), e);
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static String replaceFirst(String text, String oldString, String newString) {
        int idx = text.indexOf(oldString);
        return text.substring(0, idx) + newString + text.substring(idx + oldString.length());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=EditToolTest test`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/tool/EditTool.java src/test/java/com/mrsmith/tool/EditToolTest.java
git commit -m "feat: add edit tool for exact substring replacement"
```

---

### Task 2: TodowriteTool

**Files:**
- Create: `src/main/java/com/mrsmith/tool/TodowriteTool.java`
- Create: `src/test/java/com/mrsmith/tool/TodowriteToolTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mrsmith/tool/TodowriteToolTest.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodowriteToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void replacesListAndReturnsIt() throws Exception {
        TodowriteTool tool = new TodowriteTool();
        ToolResult result = tool.execute(JSON.readTree(
                "{\"todos\":[{\"content\":\"a\",\"status\":\"in_progress\",\"priority\":\"high\"},"
                + "{\"content\":\"b\",\"status\":\"pending\",\"priority\":\"low\"}]}"));
        assertFalse(result.error());
        assertEquals(2, tool.tasks().size());
        assertEquals("in_progress", tool.tasks().get(0).status());
        JsonNode returned = JSON.readTree(result.content());
        assertEquals(2, returned.size());
        assertEquals("a", returned.get(0).get("content").asText());
        assertEquals("pending", returned.get(1).get("status").asText());
    }

    @Test
    void replacesWholeList() throws Exception {
        TodowriteTool tool = new TodowriteTool();
        tool.execute(JSON.readTree(
                "{\"todos\":[{\"content\":\"a\",\"status\":\"completed\",\"priority\":\"high\"}]}"));
        tool.execute(JSON.readTree(
                "{\"todos\":[{\"content\":\"b\",\"status\":\"pending\",\"priority\":\"medium\"}]}"));
        assertEquals(List.of("b"),
                tool.tasks().stream().map(TodowriteTool.Task::content).toList());
    }

    @Test
    void missingTodosThrows() {
        TodowriteTool tool = new TodowriteTool();
        assertThrows(ToolException.class, () -> tool.execute(JSON.readTree("{}")));
    }

    @Test
    void invalidStatusThrows() {
        TodowriteTool tool = new TodowriteTool();
        assertThrows(ToolException.class, () -> tool.execute(JSON.readTree(
                "{\"todos\":[{\"content\":\"a\",\"status\":\"done\",\"priority\":\"high\"}]}")));
    }

    @Test
    void invalidPriorityThrows() {
        TodowriteTool tool = new TodowriteTool();
        assertThrows(ToolException.class, () -> tool.execute(JSON.readTree(
                "{\"todos\":[{\"content\":\"a\",\"status\":\"pending\",\"priority\":\"urgent\"}]}")));
    }

    @Test
    void blankContentThrows() {
        TodowriteTool tool = new TodowriteTool();
        assertThrows(ToolException.class, () -> tool.execute(JSON.readTree(
                "{\"todos\":[{\"content\":\"  \",\"status\":\"pending\",\"priority\":\"high\"}]}")));
    }

    @Test
    void resetClearsList() throws Exception {
        TodowriteTool tool = new TodowriteTool();
        tool.execute(JSON.readTree(
                "{\"todos\":[{\"content\":\"a\",\"status\":\"pending\",\"priority\":\"high\"}]}"));
        tool.reset();
        assertTrue(tool.tasks().isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=TodowriteToolTest test`
Expected: BUILD FAILURE — `cannot find symbol: class TodowriteTool`

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/mrsmith/tool/TodowriteTool.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class TodowriteTool implements Tool, Resettable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> STATUSES = Set.of("pending", "in_progress", "completed", "cancelled");
    private static final Set<String> PRIORITIES = Set.of("high", "medium", "low");

    private List<Task> tasks = List.of();

    public record Task(String content, String status, String priority) {
    }

    public List<Task> tasks() {
        return List.copyOf(tasks);
    }

    @Override
    public void reset() {
        tasks = List.of();
    }

    @Override
    public String name() {
        return "todowrite";
    }

    @Override
    public String description() {
        return "Replace the session task list with the given todos. "
                + "Status is one of pending, in_progress, completed, cancelled; "
                + "priority one of high, medium, low. "
                + "Keep exactly one in_progress while work remains, update status in real time, "
                + "and mark completed only when the work (including verification) is actually done.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode todos = schema.putObject("properties").putObject("todos");
        todos.put("type", "array");
        ObjectNode item = todos.putObject("items");
        item.put("type", "object");
        ObjectNode properties = item.putObject("properties");
        properties.putObject("content").put("type", "string");
        properties.putObject("status").put("type", "string");
        properties.putObject("priority").put("type", "string");
        item.putArray("required").add("content").add("status").add("priority");
        schema.putArray("required").add("todos");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        JsonNode todosNode = args.path("todos");
        if (!todosNode.isArray()) {
            throw new ToolException("missing required 'todos' argument");
        }
        List<Task> next = new ArrayList<>();
        int index = 0;
        for (JsonNode node : todosNode) {
            String content = node.path("content").asText(null);
            String status = node.path("status").asText(null);
            String priority = node.path("priority").asText(null);
            if (content == null || content.isBlank()) {
                throw new ToolException("todo at index " + index + " has blank content");
            }
            if (status == null || !STATUSES.contains(status)) {
                throw new ToolException("todo at index " + index + " has invalid status: " + status);
            }
            if (priority == null || !PRIORITIES.contains(priority)) {
                throw new ToolException("todo at index " + index + " has invalid priority: " + priority);
            }
            next.add(new Task(content, status, priority));
            index++;
        }
        tasks = List.copyOf(next);
        return new ToolResult(toJson(tasks), false);
    }

    private static String toJson(List<Task> list) {
        ArrayNode arr = JSON.createArrayNode();
        for (Task task : list) {
            ObjectNode o = arr.addObject();
            o.put("content", task.content());
            o.put("status", task.status());
            o.put("priority", task.priority());
        }
        return arr.toString();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=TodowriteToolTest test`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/tool/TodowriteTool.java src/test/java/com/mrsmith/tool/TodowriteToolTest.java
git commit -m "feat: add todowrite tool for the session task list"
```

---

### Task 3: QuestionTool

**Files:**
- Create: `src/main/java/com/mrsmith/tool/QuestionTool.java`
- Create: `src/test/java/com/mrsmith/tool/QuestionToolTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mrsmith/tool/QuestionToolTest.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrsmith.io.IO;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static class StubIo implements IO {
        final Deque<String> inputs;
        final List<String> lines = new ArrayList<>();

        StubIo(List<String> inputs) {
            this.inputs = new ArrayDeque<>(inputs);
        }

        @Override
        public String readLine() throws IOException {
            return inputs.poll();
        }

        @Override
        public void write(String text) {
            lines.add(text);
        }

        @Override
        public void writeLine(String line) {
            lines.add(line);
        }

        @Override
        public void writeReasoning(String text) {
            lines.add(text);
        }
    }

    private QuestionTool tool(StubIo io) {
        return new QuestionTool(io);
    }

    @Test
    void picksOptionByNumber() {
        StubIo io = new StubIo(List.of("2"));
        ToolResult result = tool(io).execute(JSON.readTree(
                "{\"questions\":[{\"question\":\"Pick one\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}]}]}"));
        assertFalse(result.error());
        assertEquals("[\"B\"]", result.content());
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("Pick one")));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("1. A")));
    }

    @Test
    void picksMultipleByCommaList() {
        StubIo io = new StubIo(List.of("1,3"));
        ToolResult result = tool(io).execute(JSON.readTree(
                "{\"questions\":[{\"question\":\"Pick\",\"multiple\":true,\"options\":[{\"label\":\"A\"},{\"label\":\"B\"},{\"label\":\"C\"}]}]}"));
        assertFalse(result.error());
        assertEquals("[[\"A\",\"C\"]]", result.content());
    }

    @Test
    void commaListWithoutMultipleIsFreeText() {
        StubIo io = new StubIo(List.of("1,3"));
        ToolResult result = tool(io).execute(JSON.readTree(
                "{\"questions\":[{\"question\":\"Pick\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"},{\"label\":\"C\"}]}]}"));
        assertFalse(result.error());
        assertEquals("[\"1,3\"]", result.content());
    }

    @Test
    void freeTextFallback() {
        StubIo io = new StubIo(List.of("custom answer"));
        ToolResult result = tool(io).execute(JSON.readTree(
                "{\"questions\":[{\"question\":\"Pick\",\"options\":[{\"label\":\"A\"}]}]}"));
        assertEquals("[\"custom answer\"]", result.content());
    }

    @Test
    void outOfRangeNumberIsError() {
        StubIo io = new StubIo(List.of("5"));
        ToolResult result = tool(io).execute(JSON.readTree(
                "{\"questions\":[{\"question\":\"Pick\",\"options\":[{\"label\":\"A\"}]}]}"));
        assertTrue(result.error());
        assertEquals("5 is not a valid option", result.content());
    }

    @Test
    void answersMultipleQuestionsInOrder() {
        StubIo io = new StubIo(List.of("1", "free"));
        ToolResult result = tool(io).execute(JSON.readTree(
                "{\"questions\":["
                + "{\"question\":\"One\",\"options\":[{\"label\":\"A\"}]},"
                + "{\"question\":\"Two\",\"options\":[{\"label\":\"B\"}]}]}"));
        assertFalse(result.error());
        assertEquals("[\"A\",\"free\"]", result.content());
    }

    @Test
    void eofYieldsEmptyAnswer() {
        StubIo io = new StubIo(List.of());
        ToolResult result = tool(io).execute(JSON.readTree(
                "{\"questions\":[{\"question\":\"Pick\",\"options\":[{\"label\":\"A\"}]}]}"));
        assertFalse(result.error());
        assertEquals("[\"\"]", result.content());
    }

    @Test
    void missingQuestionsThrows() {
        StubIo io = new StubIo(List.of());
        assertThrows(ToolException.class, () -> tool(io).execute(JSON.readTree("{}")));
    }

    @Test
    void printsHeaderAndDescription() {
        StubIo io = new StubIo(List.of("1"));
        tool(io).execute(JSON.readTree(
                "{\"questions\":[{\"header\":\"Pick\",\"question\":\"Choose\",\"options\":[{\"label\":\"A\",\"description\":\"the first\"}]}]}"));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("[Pick] Choose")));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("1. A")));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("the first")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=QuestionToolTest test`
Expected: BUILD FAILURE — `cannot find symbol: class QuestionTool`

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/mrsmith/tool/QuestionTool.java`:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.io.IO;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class QuestionTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final IO io;

    public QuestionTool(IO io) {
        this.io = io;
    }

    @Override
    public String name() {
        return "question";
    }

    @Override
    public String description() {
        return "Ask the user one or more multiple-choice questions and return the answers. "
                + "Answer by option number, or type a free-text answer.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode questions = schema.putObject("properties").putObject("questions");
        questions.put("type", "array");
        ObjectNode item = questions.putObject("items");
        item.put("type", "object");
        ObjectNode properties = item.putObject("properties");
        properties.putObject("question").put("type", "string");
        properties.putObject("header").put("type", "string");
        ObjectNode options = properties.putObject("options");
        options.put("type", "array");
        ObjectNode opt = options.putObject("items");
        opt.put("type", "object");
        ObjectNode optProps = opt.putObject("properties");
        optProps.putObject("label").put("type", "string");
        optProps.putObject("description").put("type", "string");
        opt.putArray("required").add("label");
        properties.putObject("multiple").put("type", "boolean");
        item.putArray("required").add("question");
        schema.putArray("required").add("questions");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        JsonNode questionsNode = args.path("questions");
        if (!questionsNode.isArray() || questionsNode.isEmpty()) {
            throw new ToolException("missing required 'questions' argument");
        }
        ArrayNode answers = JSON.createArrayNode();
        int index = 0;
        for (JsonNode question : questionsNode) {
            ToolResult answerResult = askOne(question, index);
            if (answerResult.error()) {
                return answerResult;
            }
            try {
                answers.add(JSON.readTree(answerResult.content()));
            } catch (IOException e) {
                throw new ToolException("could not encode answer", e);
            }
            index++;
        }
        return new ToolResult(answers.toString(), false);
    }

    private ToolResult askOne(JsonNode question, int index) {
        String text = question.path("question").asText(null);
        if (text == null || text.isBlank()) {
            throw new ToolException("question at index " + index + " has blank question text");
        }
        String header = question.path("header").asText(null);
        io.writeLine(header == null || header.isBlank() ? text : "[" + header + "] " + text);
        List<JsonNode> options = new ArrayList<>();
        for (JsonNode option : question.path("options")) {
            options.add(option);
        }
        for (int i = 0; i < options.size(); i++) {
            io.writeLine("  " + (i + 1) + ". " + options.get(i).path("label").asText());
            String description = options.get(i).path("description").asText(null);
            if (description != null && !description.isBlank()) {
                io.writeLine("    " + description);
            }
        }
        boolean multiple = question.path("multiple").asBoolean(false);
        String answer = readAnswer().trim();
        if (answer.matches("\\d+")) {
            int n = Integer.parseInt(answer) - 1;
            if (n < 0 || n >= options.size()) {
                return new ToolResult(answer + " is not a valid option", true);
            }
            return new ToolResult(encode(options.get(n).path("label").asText()), false);
        }
        if (multiple && answer.matches("\\d+(\\s*,\\s*\\d+)+")) {
            ArrayNode picked = JSON.createArrayNode();
            for (String part : answer.split(",")) {
                String p = part.trim();
                int n = Integer.parseInt(p) - 1;
                if (n < 0 || n >= options.size()) {
                    return new ToolResult(p + " is not a valid option", true);
                }
                picked.add(options.get(n).path("label").asText());
            }
            return new ToolResult(picked.toString(), false);
        }
        return new ToolResult(encode(answer), false);
    }

    private static String encode(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "\"\"";
        }
    }

    private String readAnswer() {
        try {
            String line = io.readLine();
            return line == null ? "" : line;
        } catch (IOException e) {
            throw new ToolException("could not read answer", e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=QuestionToolTest test`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/tool/QuestionTool.java src/test/java/com/mrsmith/tool/QuestionToolTest.java
git commit -m "feat: add question tool for interactive prompts"
```

---

### Task 4: Registry + factory wiring (always-on tools, IO through factory)

`ToolRegistryFactory` gains an `IO` parameter and `ToolRegistry.with` auto-adds the three tools, so `ChatCommand`, `ChatSession.applyAgent`, `ToolRegistryTest`, and every `ChatSessionTest` factory lambda must change in the same task to keep the build green.

**Files:**
- Modify: `src/main/java/com/mrsmith/tool/ToolRegistry.java`
- Modify: `src/main/java/com/mrsmith/tool/ToolRegistryFactory.java`
- Modify: `src/main/java/com/mrsmith/cli/ChatCommand.java`
- Modify: `src/main/java/com/mrsmith/chat/ChatSession.java` (applyAgent only)
- Modify: `src/test/java/com/mrsmith/tool/ToolRegistryTest.java`
- Modify: `src/test/java/com/mrsmith/chat/ChatSessionTest.java` (lambda arity only)

- [ ] **Step 1: Replace ToolRegistryTest**

Replace the entire contents of `src/test/java/com/mrsmith/tool/ToolRegistryTest.java` with:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrsmith.io.IO;
import com.mrsmith.skill.SkillCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    static class IoStub implements IO {
        @Override
        public String readLine() throws IOException {
            return null;
        }

        @Override
        public void write(String text) {
        }

        @Override
        public void writeLine(String line) {
        }

        @Override
        public void writeReasoning(String text) {
        }
    }

    private final IO io = new IoStub();

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

    private SkillCatalog emptyCatalog() {
        return SkillCatalog.discover(tempDir.resolve("no-project"), tempDir.resolve("no-global"));
    }

    private SkillCatalog catalogWith(String name) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: d\n---\nbody");
        return SkillCatalog.discover(tempDir, tempDir.resolve("nope"));
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
        ToolRegistry registry = ToolRegistry.with(
                List.of("shell", "read_file", "write_file", "list_dir", "glob", "web_fetch"),
                emptyCatalog(), io);
        assertEquals(9, registry.tools().size());
        assertTrue(registry.find("shell").isPresent());
        assertTrue(registry.find("web_fetch").isPresent());
    }

    @Test
    void builtInWithUnknownNameThrows() {
        assertThrows(ToolException.class, () -> ToolRegistry.with(List.of("nope"), emptyCatalog(), io));
    }

    @Test
    void builtinNamesCoversAllTools() {
        assertTrue(ToolRegistry.builtinNames().containsAll(
                List.of("shell", "read_file", "write_file", "list_dir", "glob", "web_fetch")));
    }

    @Test
    void alwaysOnToolsAddedEvenWhenCatalogEmpty() {
        ToolRegistry registry = ToolRegistry.with(List.of(), emptyCatalog(), io);
        assertEquals(3, registry.tools().size());
        assertTrue(registry.find("edit").isPresent());
        assertTrue(registry.find("todowrite").isPresent());
        assertTrue(registry.find("question").isPresent());
        assertFalse(registry.find("skill").isPresent());
    }

    @Test
    void addsSkillToolWhenCatalogNonEmpty() throws IOException {
        ToolRegistry registry = ToolRegistry.with(List.of(), catalogWith("coding"), io);
        assertEquals(4, registry.tools().size());
        assertTrue(registry.find("skill").isPresent());
    }

    @Test
    void alwaysOnToolsNotInBuiltinNames() {
        assertFalse(ToolRegistry.builtinNames().contains("edit"));
        assertFalse(ToolRegistry.builtinNames().contains("todowrite"));
        assertFalse(ToolRegistry.builtinNames().contains("question"));
    }

    @Test
    void alwaysOnToolsHaveExpectedApproval() {
        ToolRegistry registry = ToolRegistry.with(List.of(), emptyCatalog(), io);
        assertFalse(registry.find("edit").orElseThrow().isReadOnly());
        assertTrue(registry.find("todowrite").orElseThrow().isReadOnly());
        assertTrue(registry.find("question").orElseThrow().isReadOnly());
    }

    @Test
    void resetSessionClearsSkillToolState() throws IOException {
        SkillCatalog catalog = catalogWith("coding");
        ToolRegistry registry = ToolRegistry.with(List.of(), catalog, io);
        Tool skillTool = registry.find("skill").orElseThrow();
        skillTool.execute(JSON.readTree("{\"name\":\"coding\"}"));
        registry.resetSession();
        ToolResult result = skillTool.execute(JSON.readTree("{\"name\":\"coding\"}"));
        assertTrue(result.content().startsWith("## coding"));
    }

    @Test
    void resetSessionClearsTodowriteState() {
        ToolRegistry registry = ToolRegistry.with(List.of(), emptyCatalog(), io);
        TodowriteTool todo = (TodowriteTool) registry.find("todowrite").orElseThrow();
        todo.execute(JSON.readTree(
                "{\"todos\":[{\"content\":\"a\",\"status\":\"pending\",\"priority\":\"high\"}]}"));
        registry.resetSession();
        assertTrue(todo.tasks().isEmpty());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=ToolRegistryTest test`
Expected: BUILD FAILURE — `with(java.util.List, SkillCatalog)` no longer matches; `cannot find symbol: create(...)` mismatches in `ChatSession` once compiled.

- [ ] **Step 3: Update the implementation**

Replace the contents of `src/main/java/com/mrsmith/tool/ToolRegistry.java` with:

```java
package com.mrsmith.tool;

import com.mrsmith.io.IO;
import com.mrsmith.skill.SkillCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public final class ToolRegistry {

    private static final Map<String, Supplier<Tool>> BUILT_INS = new LinkedHashMap<>();

    static {
        BUILT_INS.put("shell", ShellTool::new);
        BUILT_INS.put("read_file", ReadFileTool::new);
        BUILT_INS.put("write_file", WriteFileTool::new);
        BUILT_INS.put("list_dir", ListDirTool::new);
        BUILT_INS.put("glob", GlobTool::new);
        BUILT_INS.put("web_fetch", WebFetchTool::new);
    }

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

    public static ToolRegistry with(List<String> toolNames, SkillCatalog catalog, IO io) {
        List<Tool> tools = new ArrayList<>();
        for (String name : toolNames) {
            Supplier<Tool> factory = BUILT_INS.get(name);
            if (factory == null) {
                throw new ToolException("Unknown tool: " + name);
            }
            tools.add(factory.get());
        }
        tools.add(new EditTool());
        tools.add(new TodowriteTool());
        tools.add(new QuestionTool(io));
        if (catalog != null && !catalog.isEmpty()) {
            tools.add(new SkillTool(catalog));
        }
        return new ToolRegistry(tools);
    }

    public static Set<String> builtinNames() {
        return BUILT_INS.keySet();
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

    public void resetSession() {
        for (Tool tool : tools) {
            if (tool instanceof Resettable resettable) {
                resettable.reset();
            }
        }
    }
}
```

Replace the contents of `src/main/java/com/mrsmith/tool/ToolRegistryFactory.java` with:

```java
package com.mrsmith.tool;

import com.mrsmith.config.AppConfig;
import com.mrsmith.io.IO;
import com.mrsmith.skill.SkillCatalog;

public interface ToolRegistryFactory {

    ToolRegistry create(AppConfig config, SkillCatalog catalog, IO io);
}
```

In `src/main/java/com/mrsmith/chat/ChatSession.java`, change `applyAgent()`:

```java
        toolRegistry = toolRegistryFactory.create(config, skills, io);
```

In `src/main/java/com/mrsmith/cli/ChatCommand.java`, change the factory lambda:

```java
                (config, skillCatalog, io) -> ToolRegistry.with(config.tools(), skillCatalog, io),
```

In `src/test/java/com/mrsmith/chat/ChatSessionTest.java`:

1. Replace `noToolsFactory()` with:
```java
    private ToolRegistryFactory noToolsFactory() {
        return (config, catalog, io) -> new ToolRegistry(List.of());
    }
```

2. Global replace `(config, catalog) -> new ToolRegistry(` → `(config, catalog, io) -> new ToolRegistry(` (updates the `registryFactory` lambdas in the tool-loop tests).
3. Global replace `(config, catalog) -> ToolRegistry.with(` → `(config, catalog, io) -> ToolRegistry.with(` (updates the skill tests' lambdas).

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=ToolRegistryTest,ChatSessionTest test`
Expected: BUILD SUCCESS (all ToolRegistryTest and ChatSessionTest tests pass; the always-on tools only change registries built via `ToolRegistry.with`, and the existing skill tests' assertions still hold).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/tool/ToolRegistry.java src/main/java/com/mrsmith/tool/ToolRegistryFactory.java src/main/java/com/mrsmith/cli/ChatCommand.java src/main/java/com/mrsmith/chat/ChatSession.java src/test/java/com/mrsmith/tool/ToolRegistryTest.java src/test/java/com/mrsmith/chat/ChatSessionTest.java
git commit -m "feat: always enable edit, todowrite, and question tools"
```

---

### Task 5: /tasks command + session integration tests

**Files:**
- Modify: `src/main/java/com/mrsmith/chat/ChatSession.java`
- Modify: `src/test/java/com/mrsmith/chat/ChatSessionTest.java`

- [ ] **Step 1: Add the failing tests**

Append to `src/test/java/com/mrsmith/chat/ChatSessionTest.java`:

```java
    @Test
    void editRequiresApproval() throws Exception {
        FakeTool edit = new FakeTool("edit", false, new ToolResult("Edited x", false));
        ToolRegistryFactory registryFactory = (config, catalog, io) -> new ToolRegistry(List.of(edit));
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_e1", "edit",
                        JSON.readTree("{\"filePath\":\"a.txt\",\"oldString\":\"x\",\"newString\":\"y\"}")),
                "answer");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "n", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        assertEquals(0, edit.calls);
        List<ChatMessage> secondSend = toolProvider.receivedHistories.get(1);
        ChatMessage last = secondSend.get(secondSend.size() - 1);
        assertTrue(last.content().contains("declined"));
    }

    @Test
    void todowriteRunsWithoutApproval() throws Exception {
        ToolRegistryFactory registryFactory = (config, catalog, io) -> ToolRegistry.with(List.of(), catalog, io);
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_t1", "todowrite",
                        JSON.readTree("{\"todos\":[{\"content\":\"a\",\"status\":\"in_progress\",\"priority\":\"high\"}]}")),
                "ok");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("tool: todowrite() -> ok")));
        List<ChatMessage> secondSend = toolProvider.receivedHistories.get(1);
        ChatMessage last = secondSend.get(secondSend.size() - 1);
        assertEquals(Role.TOOL, last.role());
        assertTrue(last.content().contains("in_progress"));
    }

    @Test
    void questionReadsAnswerWithoutApproval() throws Exception {
        ToolRegistryFactory registryFactory = (config, catalog, io) -> ToolRegistry.with(List.of(), catalog, io);
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_q1", "question",
                        JSON.readTree("{\"questions\":[{\"question\":\"Pick\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}]}]}")),
                "chosen");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "2", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        List<ChatMessage> secondSend = toolProvider.receivedHistories.get(1);
        ChatMessage last = secondSend.get(secondSend.size() - 1);
        assertEquals(Role.TOOL, last.role());
        assertEquals("[\"B\"]", last.content());
    }

    @Test
    void tasksCommandShowsEmptyList() throws Exception {
        ToolRegistryFactory registryFactory = (config, catalog, io) -> ToolRegistry.with(List.of(), catalog, io);
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/tasks", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(provider), registryFactory, emptySkills(), "a");
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("No tasks.")));
        assertTrue(provider.receivedHistories.isEmpty());
    }

    @Test
    void tasksCommandListsTasks() throws Exception {
        ToolRegistryFactory registryFactory = (config, catalog, io) -> ToolRegistry.with(List.of(), catalog, io);
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_t2", "todowrite",
                        JSON.readTree("{\"todos\":[{\"content\":\"implement edit\",\"status\":\"in_progress\",\"priority\":\"high\"},{\"content\":\"write tests\",\"status\":\"pending\",\"priority\":\"low\"}]}")),
                "done");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/tasks", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("in_progress high  implement edit")));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("pending low  write tests")));
    }

    @Test
    void resetClearsTaskList() throws Exception {
        ToolRegistryFactory registryFactory = (config, catalog, io) -> ToolRegistry.with(List.of(), catalog, io);
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_t3", "todowrite",
                        JSON.readTree("{\"todos\":[{\"content\":\"a\",\"status\":\"pending\",\"priority\":\"high\"}]}")),
                "done");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/reset", "/tasks", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("No tasks.")));
    }

    @Test
    void toolsLessAgentGetsAlwaysOnTools() throws Exception {
        SkillCatalog skills = skillsCatalog("coding", "Write Java.");
        ToolRegistryFactory registryFactory = (config, catalog, io) -> ToolRegistry.with(List.of(), catalog, io);
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(provider), registryFactory, skills, "a");
        session.run();
        List<String> names = provider.receivedTools.get(0).stream().map(Tool::name).toList();
        assertTrue(names.contains("edit"));
        assertTrue(names.contains("todowrite"));
        assertTrue(names.contains("question"));
        assertTrue(names.contains("skill"));
    }

    @Test
    void helpMentionsTasksCommand() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/help", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("/tasks")));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=ChatSessionTest test`
Expected: FAIL in the new tests (no `/tasks` handler; the always-on tests reference a registry that now includes the tools but the session still builds without `io` correctly — the failures are the `/tasks` and `/help` assertions and the `todowrite`/`question` tests until `describe`/help are updated). Any failures are resolved by Step 3.

- [ ] **Step 3: Update ChatSession**

In `src/main/java/com/mrsmith/chat/ChatSession.java`:

1. Add import:
```java
import com.mrsmith.tool.TodowriteTool;
```

2. In `handleCommand`, add the `/tasks` handling right after the `/skills` blocks (before the `switch`):
```java
        if (line.equals("/tasks")) {
            listTasks();
            return true;
        }
```

3. Update the `/help` line to:
```java
            case "/help" -> io.writeLine("Commands: /exit, /reset, /help, /usage, /agents, /agent <name>, /skills [name], /tasks. Anything else is sent to the LLM.");
```

4. Update the `describe` method's key list so the status line shows the file for `edit`:
```java
        for (String key : List.of("command", "path", "filePath", "pattern", "url")) {
```

5. Add the new private methods after `skillTool()`:
```java
    private void listTasks() {
        TodowriteTool todoTool = todoTool();
        if (todoTool == null) {
            io.writeLine("No task list available.");
            return;
        }
        List<TodowriteTool.Task> tasks = todoTool.tasks();
        if (tasks.isEmpty()) {
            io.writeLine("No tasks.");
            return;
        }
        StringBuilder report = new StringBuilder("Tasks:");
        for (TodowriteTool.Task task : tasks) {
            report.append("\n  ").append(task.status()).append(" ")
                    .append(task.priority()).append("  ").append(task.content());
        }
        io.writeLine(report.toString());
    }

    private TodowriteTool todoTool() {
        Optional<Tool> tool = toolRegistry.find("todowrite");
        if (tool.isPresent() && tool.get() instanceof TodowriteTool todoTool) {
            return todoTool;
        }
        return null;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=ChatSessionTest test`
Expected: BUILD SUCCESS — all existing tests plus the 8 new ones pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ChatSession.java src/test/java/com/mrsmith/chat/ChatSessionTest.java
git commit -m "feat: add /tasks command to show the session task list"
```

---

### Task 6: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full suite**

Run: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: BUILD SUCCESS — a final `Tests run: N, Failures: 0, Errors: 0, Skipped: 0` line with N = 248 (the previous 213 tests plus 35 new: 8 edit + 7 todowrite + 9 question + 3 registry + 8 session).

- [ ] **Step 2: Confirm the working tree is clean**

Run: `git status --short`
Expected: nothing (all changes committed).
