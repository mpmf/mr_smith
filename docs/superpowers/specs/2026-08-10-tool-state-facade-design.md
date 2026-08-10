# Design: Decouple `ChatSession` from Tool Internals

Date: 2026-08-10

## Context

`ChatSession` reaches into tool implementations to power its `/skills` and
`/tasks` commands: it does `instanceof SkillTool` / `instanceof TodowriteTool`
on registry lookups and calls their internal methods (`isLoaded`, `load`,
`tasks`). This couples the session orchestrator to specific tool classes. The
skill-loading logic is also duplicated between the `/skills <name>` REPL command
and the `skill` tool (`find` → `load` → `render`), so the two can drift.

## Goal

- Expose tool state as a narrow `ToolState` seam so `ChatSession` no longer
  knows about concrete tool classes.
- Unify the skill-loading path so the `skill` tool and the `/skills <name>`
  command share one implementation.

## Scope

- New `com.mrsmith.tool.ToolState` interface.
- `ToolRegistry implements ToolState` (delegating to its tools).
- `SkillTool` gains `loaded()` and `loadSkill(String)` returning a `SkillLoad`
  result; `execute` routes through it; `isLoaded` is removed.
- `ChatSession` holds a `ToolState` and uses it for `/skills`, `/skills <name>`,
  and `/tasks`.
- Tests updated/added.

## Non-Goals

- No change to the `Tool` contract or to tool behavior/output.
- No change to `TodowriteTool` (its `tasks()` already returns a read-only copy).
- No change to how the REPL commands are surfaced or their exact messages.

## Architecture

### `ToolState` (new)

`com.mrsmith.tool.ToolState`:

```java
public interface ToolState {

    Set<String> loadedSkills();

    SkillTool.SkillLoad loadSkill(String name);

    List<TodowriteTool.Task> tasks();
}
```

`ChatSession` depends only on this seam.

### `ToolRegistry implements ToolState`

`ToolRegistry` (which owns the tools and is allowed to know them) delegates:

- `loadedSkills()` → the `skill` tool's `loaded()` set, else `Set.of()`.
- `loadSkill(name)` → the `skill` tool's `loadSkill`, else
  `SkillTool.SkillLoad.unknown(name)`.
- `tasks()` → the `todowrite` tool's `tasks()`, else `List.of()`.

The `instanceof` moves from `ChatSession` into `ToolRegistry`'s private helpers.

### `SkillTool` unified loading

`SkillTool` gains a nested result record and a single loading method used by
both callers:

```java
public record SkillLoad(boolean loaded, boolean error, String content, String message) {

    static SkillLoad unknown(String name) { ... }           // "Unknown skill: X", error
    static SkillLoad alreadyLoaded(String name) { ... }     // "Skill 'X' is already loaded.", not error
    static SkillLoad ok(String content) { ... }             // rendered body
}

public SkillLoad loadSkill(String name) {
    if (catalog.find(name).isEmpty()) return SkillLoad.unknown(name);
    if (!load(name)) return SkillLoad.alreadyLoaded(name);
    return SkillLoad.ok(catalog.render(name));
}
```

- `Tool.execute` maps `SkillLoad` → `ToolResult` (error → error result; else
  content if loaded, message otherwise). Exact existing output preserved.
- `isLoaded(String)` is removed; `loaded()` returns `Set.copyOf(loaded)`. The
  idempotent `load(String)` stays (used internally by `loadSkill` and by tests).

### `ChatSession`

- New field `private ToolState toolState;`, assigned `toolRegistry` in
  `applyAgent()`.
- `skillTool()` / `todoTool()` helper methods are deleted.
- `/skills`: marker via `toolState.loadedSkills().contains(name)`.
- `/skills <name>`: `SkillLoad result = toolState.loadSkill(name);` — on
  `result.error() || !result.loaded()` print `result.message()`; else append the
  rendered content to history/context, record the skill load, and print
  "Loaded skill: X".
- `/tasks`: `List<TodowriteTool.Task> tasks = toolState.tasks();` — empty →
  "No tasks.", else the existing report. The old `todoTool == null` "No task
  list available." branch is dropped (dead: `todowrite` is always-on).

## Error Handling

| Scenario | Behavior (unchanged) |
|---|---|
| `/skills <name>` unknown skill | "Unknown skill: X" |
| `/skills <name>` already loaded | "Skill 'X' is already loaded." |
| `/skills <name>` loads | rendered content appended; "Loaded skill: X" |
| `skill` tool called with unknown name | error result "Unknown skill: X" |
| `skill` tool called when already loaded | ok result "Skill 'X' is already loaded." |
| No `skill`/`todowrite` tool in registry | facade returns empty set / unknown / empty list |

## Testing

- `SkillToolTest.tracksLoadedNames` — uses `loaded()` instead of `isLoaded()`.
- `SkillToolTest` — new direct `loadSkill` assertions (ok / unknown /
  already-loaded), in addition to the existing `execute`-level tests which stay.
- `ToolRegistryTest` — new cases: `loadedSkills()` empty without skills,
  `loadSkill` returns unknown when no skill tool, `tasks()` empty without
  todowrite.
- `ChatSessionTest` — existing `/skills`, `/skills coding`, `/tasks` tests pass
  unchanged (behavior preserved).
- Full suite stays green.
