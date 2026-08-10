# Tool-State Facade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decouple `ChatSession` from concrete tool classes by exposing tool state through a narrow `ToolState` seam implemented by `ToolRegistry`, and unify the skill-loading path so the `skill` tool and the `/skills <name>` command share one `SkillTool.loadSkill`.

**Architecture:** New `tool.ToolState` interface (`loadedSkills()`, `loadSkill(name)`, `tasks()`). `ToolRegistry implements ToolState`, moving the `instanceof` lookups out of `ChatSession`. `SkillTool` gains a `SkillLoad` result record + `loadSkill(String)` used by both `execute` and the command, plus `loaded()`; the now-unused `isLoaded` is removed. `ChatSession` holds a `ToolState` and uses it for `/skills`, `/skills <name>`, `/tasks`. Spec: `docs/superpowers/specs/2026-08-10-tool-state-facade-design.md`.

**Tech Stack:** Java 21, JUnit 5 (Jupiter), Maven.

---

## File Structure

**Create (main):**
- `src/main/java/com/mrsmith/tool/ToolState.java`

**Modify (main):**
- `src/main/java/com/mrsmith/tool/SkillTool.java` — `SkillLoad` + `loadSkill` + `loaded()`; `execute` routes through it
- `src/main/java/com/mrsmith/tool/ToolRegistry.java` — implements `ToolState`
- `src/main/java/com/mrsmith/chat/ChatSession.java` — uses `ToolState`; deletes `skillTool()`/`todoTool()`

**Modify (test):**
- `src/test/java/com/mrsmith/tool/SkillToolTest.java` — `loadSkill` cases; `tracksLoadedNames` uses `loaded()`
- `src/test/java/com/mrsmith/tool/ToolRegistryTest.java` — `ToolState` cases

---

### Task 1: `SkillTool` unified loading + `ToolState` + `ToolRegistry`

**Files:**
- Modify: `src/main/java/com/mrsmith/tool/SkillTool.java`
- Create: `src/main/java/com/mrsmith/tool/ToolState.java`
- Modify: `src/main/java/com/mrsmith/tool/ToolRegistry.java`
- Modify: `src/test/java/com/mrsmith/tool/SkillToolTest.java`
- Modify: `src/test/java/com/mrsmith/tool/ToolRegistryTest.java`

- [ ] **Step 1: Write the failing test for `loadSkill`**

Append to `src/test/java/com/mrsmith/tool/SkillToolTest.java`:

```java
    @Test
    void loadSkillReportsStates() throws IOException {
        SkillTool tool = new SkillTool(catalog("coding", "Write Java.", "run tests"));
        SkillTool.SkillLoad loaded = tool.loadSkill("coding");
        assertTrue(loaded.loaded());
        assertFalse(loaded.error());
        assertTrue(loaded.content().contains("run tests"));
        SkillTool.SkillLoad again = tool.loadSkill("coding");
        assertFalse(again.loaded());
        assertEquals("Skill 'coding' is already loaded.", again.message());
        SkillTool.SkillLoad unknown = tool.loadSkill("nope");
        assertTrue(unknown.error());
        assertEquals("Unknown skill: nope", unknown.message());
    }
```

Run: `mvn -q -Dtest=SkillToolTest test`
Expected: FAIL — compilation error, `SkillTool.SkillLoad` / `loadSkill` not defined.

- [ ] **Step 2: Update `SkillTool`**

Replace the full content of `src/main/java/com/mrsmith/tool/SkillTool.java` with:

```java
package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.skill.SkillCatalog;
import com.mrsmith.util.Json;

import java.util.HashSet;
import java.util.Set;

public final class SkillTool implements Tool, Resettable {

    private static final ObjectMapper JSON = Json.MAPPER;

    private final SkillCatalog catalog;
    private final Set<String> loaded = new HashSet<>();

    public SkillTool(SkillCatalog catalog) {
        this.catalog = catalog;
    }

    public record SkillLoad(boolean loaded, boolean error, String content, String message) {

        static SkillLoad unknown(String name) {
            return new SkillLoad(false, true, null, "Unknown skill: " + name);
        }

        static SkillLoad alreadyLoaded(String name) {
            return new SkillLoad(false, false, null, "Skill '" + name + "' is already loaded.");
        }

        static SkillLoad ok(String content) {
            return new SkillLoad(true, false, content, null);
        }
    }

    public Set<String> loaded() {
        return Set.copyOf(loaded);
    }

    public boolean isLoaded(String name) {
        return loaded.contains(name);
    }

    public boolean load(String name) {
        return loaded.add(name);
    }

    public SkillLoad loadSkill(String name) {
        if (catalog.find(name).isEmpty()) {
            return SkillLoad.unknown(name);
        }
        if (!load(name)) {
            return SkillLoad.alreadyLoaded(name);
        }
        return SkillLoad.ok(catalog.render(name));
    }

    @Override
    public void reset() {
        loaded.clear();
    }

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public String description() {
        return "Load a skill's instructions into the conversation. "
                + "Use a name from the Available skills list in the system prompt.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("name").put("type", "string");
        schema.putArray("required").add("name");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String name = args.path("name").asText(null);
        if (name == null || name.isBlank()) {
            throw new ToolException("missing required 'name' argument");
        }
        SkillLoad result = loadSkill(name);
        if (result.error()) {
            return new ToolResult(result.message(), true);
        }
        return new ToolResult(result.loaded() ? result.content() : result.message(), false);
    }
}
```

Note: `isLoaded(String)` is kept here (still used by `ChatSession` until Task 2); Task 2 removes it.

Run: `mvn -q -Dtest=SkillToolTest test`
Expected: PASS — existing tests (via `execute`) and the new `loadSkillReportsStates` all green.

- [ ] **Step 3: Create `ToolState`**

Create `src/main/java/com/mrsmith/tool/ToolState.java`:

```java
package com.mrsmith.tool;

import java.util.List;
import java.util.Set;

public interface ToolState {

    Set<String> loadedSkills();

    SkillTool.SkillLoad loadSkill(String name);

    List<TodowriteTool.Task> tasks();
}
```

- [ ] **Step 4: Make `ToolRegistry` implement `ToolState`**

In `src/main/java/com/mrsmith/tool/ToolRegistry.java`:

1. Change the class declaration `public final class ToolRegistry {` to `public final class ToolRegistry implements ToolState {`.
2. Add these methods (e.g., after `resetSession`):

```java
    @Override
    public Set<String> loadedSkills() {
        return skillTool().map(SkillTool::loaded).orElse(Set.of());
    }

    @Override
    public SkillTool.SkillLoad loadSkill(String name) {
        return skillTool().map(tool -> tool.loadSkill(name))
                .orElseGet(() -> SkillTool.SkillLoad.unknown(name));
    }

    @Override
    public List<TodowriteTool.Task> tasks() {
        Tool tool = byName.get("todowrite");
        return tool instanceof TodowriteTool todo ? todo.tasks() : List.of();
    }

    private Optional<SkillTool> skillTool() {
        Tool tool = byName.get("skill");
        return tool instanceof SkillTool skillTool ? Optional.of(skillTool) : Optional.empty();
    }
```

`Set`, `List`, and `Optional` are already imported in `ToolRegistry`; `SkillTool` and `TodowriteTool` are in the same package.

- [ ] **Step 5: Add `ToolRegistryTest` cases**

Append to `src/test/java/com/mrsmith/tool/ToolRegistryTest.java`:

```java
    @Test
    void toolStateEmptyWithoutSkills() {
        ToolRegistry registry = ToolRegistry.with(List.of(), emptyCatalog(), io, taskRunner);
        assertTrue(registry.loadedSkills().isEmpty());
        SkillTool.SkillLoad load = registry.loadSkill("nope");
        assertTrue(load.error());
        assertEquals("Unknown skill: nope", load.message());
        assertTrue(registry.tasks().isEmpty());
    }

    @Test
    void toolStateTracksLoadedSkills() throws IOException {
        ToolRegistry registry = ToolRegistry.with(List.of(), catalogWith("coding"), io, taskRunner);
        SkillTool.SkillLoad load = registry.loadSkill("coding");
        assertTrue(load.loaded());
        assertTrue(registry.loadedSkills().contains("coding"));
        SkillTool.SkillLoad again = registry.loadSkill("coding");
        assertFalse(again.loaded());
        assertEquals("Skill 'coding' is already loaded.", again.message());
    }
```

- [ ] **Step 6: Run the full suite**

Run: `mvn test 2>&1 | grep -E "Tests run: [0-9]+, Failures"`
Expected: BUILD SUCCESS — 315 tests (312 + 1 `loadSkillReportsStates` + 2 `ToolRegistryTest`).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mrsmith/tool/SkillTool.java src/main/java/com/mrsmith/tool/ToolState.java src/main/java/com/mrsmith/tool/ToolRegistry.java src/test/java/com/mrsmith/tool/SkillToolTest.java src/test/java/com/mrsmith/tool/ToolRegistryTest.java
git commit -m "feat: expose tool state through a ToolState facade"
```

---

### Task 2: Decouple `ChatSession` and remove `isLoaded`

**Files:**
- Modify: `src/main/java/com/mrsmith/chat/ChatSession.java`
- Modify: `src/main/java/com/mrsmith/tool/SkillTool.java`
- Modify: `src/test/java/com/mrsmith/tool/SkillToolTest.java`

- [ ] **Step 1: Update `ChatSession` to use `ToolState`**

In `src/main/java/com/mrsmith/chat/ChatSession.java`:

1. Add imports `import com.mrsmith.tool.ToolState;` and `import java.util.Set;`. Keep the existing `SkillTool` and `TodowriteTool` imports (still used as result types). Remove the imports `import com.mrsmith.tool.Tool;` and `import java.util.Optional;` — their only uses are in the `skillTool()`/`todoTool()` helpers deleted in step 4.
2. Add a field `private ToolState toolState;` next to `toolRegistry`.
3. In `applyAgent()`, after `toolRegistry = toolRegistryFactory.create(runtime, skills, io, subAgentRunner);` add:
   ```java
        toolState = toolRegistry;
   ```
4. Delete the `skillTool()` and `todoTool()` helper methods entirely.
5. Replace `listSkills()` with:
   ```java
    private void listSkills() {
        if (skills.isEmpty()) {
            io.writeLine("No skills found.");
            return;
        }
        Set<String> loaded = toolState.loadedSkills();
        StringBuilder report = new StringBuilder("Skills:");
        for (String name : skills.names()) {
            Skill skill = skills.find(name).orElseThrow();
            String marker = loaded.contains(name) ? "*" : "";
            report.append("\n  ").append(name).append(marker).append("  ").append(skill.description());
        }
        io.writeLine(report.toString());
    }
   ```
6. Replace `loadSkill(String)` with:
   ```java
    private void loadSkill(String name) {
        SkillTool.SkillLoad result = toolState.loadSkill(name);
        if (result.error() || !result.loaded()) {
            io.writeLine(result.message());
            return;
        }
        String content = result.content();
        history.add(new ChatMessage(Role.SYSTEM, content));
        contextBuilder.appendSystem(content);
        appendSkillLoad(name);
        io.writeLine("Loaded skill: " + name);
    }
   ```
7. Replace `listTasks()` with:
   ```java
    private void listTasks() {
        List<TodowriteTool.Task> tasks = toolState.tasks();
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
   ```

- [ ] **Step 2: Remove the now-unused `isLoaded` from `SkillTool`**

In `src/main/java/com/mrsmith/tool/SkillTool.java`, delete the `isLoaded(String)` method (its only main-source caller, `ChatSession.listSkills`, now uses `loadedSkills()`).

- [ ] **Step 3: Update `SkillToolTest.tracksLoadedNames`**

In `src/test/java/com/mrsmith/tool/SkillToolTest.java`, replace:

```java
    @Test
    void tracksLoadedNames() throws IOException {
        SkillTool tool = new SkillTool(catalog("coding", "Write Java.", "b"));
        assertTrue(tool.load("coding"));
        assertTrue(tool.isLoaded("coding"));
        assertFalse(tool.load("coding"));
    }
```

with:

```java
    @Test
    void tracksLoadedNames() throws IOException {
        SkillTool tool = new SkillTool(catalog("coding", "Write Java.", "b"));
        assertTrue(tool.load("coding"));
        assertTrue(tool.loaded().contains("coding"));
        assertFalse(tool.load("coding"));
    }
```

- [ ] **Step 4: Run the full suite**

Run: `mvn test 2>&1 | grep -E "Tests run: [0-9]+, Failures"`
Expected: BUILD SUCCESS — 315 tests. `ChatSessionTest`'s `/skills`, `/skills coding`, and `/tasks` tests pass unchanged (behavior preserved).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ChatSession.java src/main/java/com/mrsmith/tool/SkillTool.java src/test/java/com/mrsmith/tool/SkillToolTest.java
git commit -m "refactor: drive /skills and /tasks through a ToolState facade"
```
