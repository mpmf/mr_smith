# Design: Agentic Tools (edit, todowrite, question)

Date: 2026-08-08

## Problem

Mr Smith's agents can call tools, but they cannot drive the three interactive
primitives that make a coding agent productive: editing files by exact
substring replacement, maintaining a live task list, and asking the user
questions. The `skills` feature made agentic workflows loadable, but those
workflows need these tools to act. This design adds three always-available
built-in tools that mirror the corresponding host harness tools (by name,
parameters, and behavior): `edit`, `todowrite`, and `question`.

## Goal

- `edit` — replace an exact substring in a file, failing unless it matches
  exactly once (or `replaceAll` is set).
- `todowrite` — replace the session's task list (content/status/priority).
- `question` — prompt the user with numbered options and return the answer(s).
- All three are available to every agent automatically (like the `skill`
  tool), require no config, and integrate with the existing tool loop,
  approval, transcripts, and session reset.

## Scope

- Three new built-in tools in `com.mrsmith.tool`: `EditTool`, `TodowriteTool`,
  `QuestionTool`.
- `ToolRegistry.with` auto-adds them; the registry factory carries the `IO`
  so `QuestionTool` can prompt.
- Session-scoped task list with reset semantics (`/reset`, `/agent`).
- `/tasks` REPL command to display the task list; `/help` updated.

## Non-Goals

- No new config fields (the three tools are always available, not
  per-agent-selectable; they are not part of `builtinNames()`, mirroring
  `skill`).
- No task-list persistence across sessions (in-memory only).
- No fuzzy/partial string matching for `edit` (exact substring only, like the
  host `edit` tool).
- No multi-question batching beyond the `questions` array; no re-prompting on
  invalid input (a non-number answer is treated as free text).
- No `TODAY`-style date helpers or other host-tool imports beyond the three.

## Architecture

### EditTool

Name: `edit`. Not read-only (modifies files) → approval prompt, like
`write_file`.

Parameters:

```json
{ "type": "object",
  "properties": {
    "filePath": { "type": "string" },
    "oldString": { "type": "string" },
    "newString": { "type": "string" },
    "replaceAll": { "type": "boolean" }
  },
  "required": ["filePath", "oldString", "newString"] }
```

`execute`:

- Missing `filePath`, blank `filePath`/`oldString`, or missing `newString` →
  `ToolException`. A blank `newString` is allowed and deletes the matched
  text (mirrors the host `edit` tool).
- Resolve `filePath` with the existing file-tool path rules (`ToolPaths`):
  relative to CWD, normalized, must stay inside the CWD root, symlink
  containment enforced (same as `read_file`/`write_file`).
- The file must be a regular file; if it exceeds 1 MiB → error result (checked
  by size before reading, matching `read_file`).
- The file must decode losslessly as UTF-8; otherwise → error result and the
  file is left untouched (refuses to corrupt non-UTF-8 content).
- Count occurrences of `oldString` (exact substring):
  - 0 → `ToolResult("oldString not found in file", error=true)`
  - more than 1 and `replaceAll` not true →
    `ToolResult("found <n> matches; set replaceAll=true or provide a more specific oldString", error=true)`
- Replace: exactly one occurrence → replace it; `replaceAll` → replace all.
  `newString` may equal `oldString`? No — the host tool requires them to
  differ. If they are equal → `ToolResult("newString must differ from oldString", error=true)`.
- Write the full updated content back.
- Return `ToolResult("Edited <filePath> (<n> replacements)", error=false)`.

### TodowriteTool

Name: `todowrite`. Marked read-only for approval purposes (it changes only
in-memory session state, never the system) → runs without a prompt.

Parameters:

```json
{ "type": "object",
  "properties": {
    "todos": { "type": "array",
      "items": { "type": "object",
        "properties": {
          "content": { "type": "string" },
          "status": { "type": "string",
            "enum": ["pending", "in_progress", "completed", "cancelled"] },
          "priority": { "type": "string",
            "enum": ["high", "medium", "low"] }
        },
        "required": ["content", "status", "priority"] } }
  },
  "required": ["todos"] }
```

State: `List<Task>` where `Task(String content, String status, String priority)`,
owned by the tool instance. `execute`:

- `todos` missing or not an array → `ToolException`.
- Any item with blank `content` or an invalid `status`/`priority` →
  `ToolException` naming the invalid item.
- Replace the stored list wholesale (declarative full-state, like the host
  `todowrite`).
- Return `ToolResult(<JSON of the current list>, error=false)` so the model
  sees the stored state.

`TodowriteTool implements Resettable` — `reset()` clears the list.
`ToolRegistry.resetSession()` (called in `startFreshSession`) clears it on
startup, `/reset`, and `/agent`. Exposes `List<Task> tasks()` for `/tasks`.

The tool's `description()` carries the host tool's behavioral rules: keep
exactly one `in_progress` while work remains, mark `completed` only when
actually done (including verification), update status in real time, break
large work into specific actionable items.

### QuestionTool

Name: `question`. Marked read-only (it is the interaction, no system side
effects) → runs without a prompt. Constructed with the session `IO`.

Parameters:

```json
{ "type": "object",
  "properties": {
    "questions": { "type": "array",
      "items": { "type": "object",
        "properties": {
          "question": { "type": "string" },
          "header": { "type": "string" },
          "options": { "type": "array",
            "items": { "type": "object",
              "properties": {
                "label": { "type": "string" },
                "description": { "type": "string" }
              },
              "required": ["label"] } },
          "multiple": { "type": "boolean" }
        },
        "required": ["question"] } }
  },
  "required": ["questions"] }
```

`execute` — for each question, in order:

- Print `[<header>] <question>` (header omitted if blank), then one line per
  option: `  <i+1>. <label>` followed by `    <description>` when present.
- Read one line via `io.readLine()`.
- Parse the trimmed answer:
  - a single number in range → that option's label;
  - a comma-separated list of numbers and `multiple` is true → the
    corresponding labels;
  - a comma-separated list when `multiple` is false → free text (the raw
    input);
  - any out-of-range number (single or in a list) → `ToolResult("<n> is not a valid option", error=true)`;
  - anything else (including blank) → the raw text as a free-form answer.
- No re-prompting; `null` (EOF) counts as an empty answer.

Return `ToolResult(<JSON array of the answers, in question order>, error=false)`.

### Registry & factory

- `ToolRegistryFactory` becomes
  `ToolRegistry create(AppConfig config, SkillCatalog catalog, IO io)`.
- `ToolRegistry.with(List<String> toolNames, SkillCatalog catalog, IO io)`
  builds the named built-ins, then **always** adds `new EditTool()`,
  `new TodowriteTool()`, `new QuestionTool(io)`, and finally
  `new SkillTool(catalog)` when the catalog is non-empty.
- The three new tools are not in `builtinNames()` (config cannot opt into
  always-on tools; listing them in an agent's `tools` array is a config error,
  consistent with `skill`).
- `ChatSession.applyAgent()` passes its `io`:
  `toolRegistry = toolRegistryFactory.create(config, skills, io)`.

### /tasks command

- `/tasks` prints the current task list from `todowrite`, one per line:
  `<status> <priority>  <content>` (two spaces before the content, mirroring
  the `/skills` layout; e.g. `in_progress high  implement edit tool`).
  Empty list → `No tasks.` No `todowrite` tool in the registry → `No task list available.`
- `/help` gains `/tasks`.

## Error handling

| Scenario | Behavior |
|---|---|
| `edit` — `oldString` not found | error result; file unchanged |
| `edit` — multiple matches, no `replaceAll` | error result; file unchanged |
| `edit` — `newString == oldString` | error result; file unchanged |
| `edit` — path escapes CWD / symlink escape | `ToolException`, handled as error result |
| `edit` — file > 1 MiB | error result; file unchanged |
| `edit` — not losslessly UTF-8 | error result; file unchanged |
| `todowrite` — invalid status/priority/blank content | `ToolException` naming the invalid item |
| `question` — out-of-range option number | error result |
| `question` — EOF (Ctrl-D) | empty answer returned |

## Testing

- `EditToolTest` — single-match replace; no-match error; multiple-match error
  without `replaceAll`; `replaceAll` replaces all; `newString == oldString`
  error; containment guard (path escapes CWD rejected); content written back.
- `TodowriteToolTest` — replaces the list; returns the list JSON; invalid
  status/priority/blank content errors; `reset()` clears.
- `QuestionToolTest` (with `StubIo`) — single-number pick; comma list with
  `multiple:true`; free-text fallback; comma list without `multiple` is free
  text; out-of-range number error; multiple questions answered in order; EOF
  yields empty answer.
- `ToolRegistryTest` — edit/todowrite/question always added (empty catalog →
  3 tools; non-empty → 4 including `skill`); not in `builtinNames()`; factory
  signature carries `IO`.
- `ChatSessionTest` — `edit` prompts for approval and applies on `y`; declined
  on `n`; `todowrite` and `question` run without prompting; `/tasks` prints the
  list and `No tasks.` when empty; `/reset` clears the task list; tools-less
  agent still gets all always-on tools; `/help` mentions `/tasks`.
