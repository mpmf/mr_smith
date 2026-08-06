# Design: Skills

Date: 2026-08-05

## Problem

Mr Smith's agents can call tools, but all reusable know-how lives either in the
agent's `systemPrompt` (loaded on every turn, whether needed or not) or in the
model's head. There is no way to package a repeatable procedure — coding
conventions, a release checklist, a debugging workflow — as a file that the
model loads only when relevant. This design adds **skills**: markdown
instruction bundles discovered on disk and loaded on demand into the active
context window.

## Goal

- Skills are directories containing a `SKILL.md` with YAML frontmatter
  (`name`, `description`) and a markdown body, discovered from the project and
  a global directory.
- The system prompt always lists the **available** skills (name + description
  only) so the model knows what exists.
- The model **loads** a skill by calling a built-in `skill` tool; the rendered
  body enters the conversation and stays for the rest of the session.
- The user can list skills and load one manually with `/skills [name]`.
- The full skill body never goes into the system prompt.

## Scope

- Skill discovery (project + global dirs, project wins on name collision).
- A `skill` built-in tool (read-only, always available when skills exist).
- Available-skills index appended to the system prompt.
- `/skills` REPL command: list, and manual load by name.
- Skill bodies persist in the context window until `/reset` or an agent switch.
- Transcript record for manual skill loads.

## Non-Goals

- No skill authoring UI; skills are files.
- No per-agent skill permissions or allowlists — the `skill` tool is available
  to every agent (mirrors opencode's default).
- No user-defined tools brought in by skills; bundled scripts are read/run via
  the existing `read_file`/`shell` tools.
- No "unload" of a loaded skill (standard harness behavior: content stays once
  loaded).
- No live re-discovery of skill files mid-session (catalog is fixed at
  startup).

## Architecture

### Skill model

New package `com.mrsmith.skill`:

```java
public record Skill(String name, String description, String body, Path resourceDir) {}
```

Each skill is a directory `<name>/SKILL.md`:

```markdown
---
name: coding
description: Guidance for writing idiomatic Java in this project.
---

Always run `mvn -q test` before claiming work is done.
- Follow the existing package layout in src/main.
...
```

- `name` and `description` come from the YAML frontmatter; the body is the
  markdown content after the frontmatter block.
- `name` must match the Agent Skills name rule `^[a-z0-9]+(-[a-z0-9]+)*$` and
  equal the containing directory name.
- `resourceDir` = the skill's directory, so the model can `read_file`/`glob`
  bundled scripts and references via the existing file tools.

**Frontmatter parsing** is a small hand-rolled parser handling `key: value`
lines and single-line string values (`name`, `description`), with surrounding
quotes stripped. Full YAML is out of scope; no new dependency is added.

### Discovery

`SkillCatalog` scans two roots (skipped silently if a root does not exist):

| Root | Path |
|---|---|
| Project | `<cwd>/skills/<name>/SKILL.md` |
| Global | `~/.config/mrsmith/skills/<name>/SKILL.md` |

- A `SKILL.md` is a valid skill iff the frontmatter parses with a non-blank
  `name` and `description` and the name is well-formed.
- **Malformed** files (missing name/description, bad name, unparseable
  frontmatter) are **skipped with a warning to stderr** — skills are optional
  extras and must not break unrelated sessions.
- **Name collision** across roots: project wins, silently.
- Catalog is loaded once at startup and is immutable for the session.

### Configurable skill directories

The two roots are configurable in the config file, with the current values as
defaults:

| Config key | Default | Anchor |
|---|---|---|
| `projectSkillsDir` | `"skills"` → `<user.dir>/skills` | `user.dir` (CWD) |
| `globalSkillsDir` | `".config/mrsmith/skills"` → `<user.home>/.config/mrsmith/skills` | `user.home` |

- Both keys are optional; omitted keys fall back to the defaults above, so an
  existing config behaves exactly as before.
- A configured value that is a **relative** path is resolved against its anchor
  (`user.dir` for project, `user.home` for global); an **absolute** value is
  used as-is.
- `ConfigLoader` resolves the values to absolute `Path`s and `AgentCatalog`
  carries them (`projectSkillsDir()`, `globalSkillsDir()`).
  `ChatCommand` calls `SkillCatalog.discover(catalog.projectSkillsDir(),
  catalog.globalSkillsDir())` — no hardcoded paths.
- The defaults are defined once (`AgentCatalog.defaultProjectSkillsDir()` /
  `defaultGlobalSkillsDir()`) so config parsing and the convenience
  constructor cannot drift.

Example:

```json
{
  "providers": [ ... ],
  "agents": [ ... ],
  "defaultAgent": "coder",
  "projectSkillsDir": "skills",
  "globalSkillsDir": ".config/mrsmith/skills"
}
```

### SkillCatalog

```java
public final class SkillCatalog {
    public static SkillCatalog discover(Path projectDir, Path globalDir);
    public Set<String> names();
    public Optional<Skill> find(String name);
    public boolean isEmpty();
    public String indexText();        // "Available skills:\n- name: description\n..."
    public String render(String name); // header + description + resource path + body
}
```

`render(name)` produces the text injected when the skill is loaded:

```
## <name>
<description>
Resources at: <resourceDir>

<body>
```

`indexText()` produces the system-prompt listing:

```
Available skills:
- coding: Guidance for writing idiomatic Java in this project.
- git-release: Create consistent releases and changelogs.
```

### Skill tool

New built-in tool in `com.mrsmith.tool`:

```java
public final class SkillTool implements Tool {
    // name() = "skill"
    // description() = "Load a skill's instructions into the conversation. Use a name from the Available skills list in the system prompt."
    // parametersSchema() = { "type": "object", "properties": { "name": { "type": "string" } }, "required": ["name"] }
    // isReadOnly() = true
}
```

`execute({ "name": X })`:

- unknown name → `ToolResult("Unknown skill: X", error=true)`
- already loaded this session → `ToolResult("Skill 'X' is already loaded.", error=false)` (dedupe; no second copy)
- otherwise → marks loaded and returns `ToolResult(catalog.render(X), error=false)`

The body arrives in the context as a `role:tool` result message (the existing
tool loop), which persists for the rest of the session — the standard skill
lifecycle.

**Always available.** When the catalog is non-empty, `SkillTool` is added to
every agent's `ToolRegistry` regardless of the agent's `tools` array. An empty
catalog means no skill tool and no index (agents behave exactly as today).

**Dedupe state** lives in the `SkillTool` instance. The tool path and the
`/skills <name>` manual path share the same registry instance, so the loaded
set is consistent across both.

### Registry wiring

- `ToolRegistry.with(List<String> toolNames, SkillCatalog catalog)` adds
  `new SkillTool(catalog)` when the catalog is non-empty, alongside the named
  tools from the built-in set.
- `ToolRegistryFactory` becomes `ToolRegistry create(AppConfig config,
  SkillCatalog catalog)`.
- `ToolRegistry.resetSession()` resets the `SkillTool`'s loaded set (via a
  small `Resettable` marker interface implemented by `SkillTool`).
- `ChatSession` gains the catalog (constructor) and calls
  `toolRegistry.resetSession()` in `startFreshSession()`, covering startup,
  `/reset`, and `/agent` switch (the latter also recreates the registry).

### System prompt composition

At each session start, `ChatSession` composes the system prompt:

```
<agent system prompt>

Available skills:
- coding: Guidance for writing idiomatic Java in this project.
- git-release: Create consistent releases and changelogs.
```

- Built once per session from the static catalog; there is no enable/disable
  state to recompute (skills load on demand).
- Empty catalog → index omitted, system prompt identical to today.

### /skills command

- `/skills` — lists every discovered skill with its description, marking
  loaded ones with `*` (e.g. `coding*`).
- `/skills <name>` — manual load. Unknown name → `Unknown skill: <name>`.
  Already loaded → `Skill '<name>' is already loaded.` Otherwise renders the
  body and:
  - appends it to the context as a `SYSTEM` message via new
    `ContextBuilder.appendSystem(String)` (and to `history`);
  - writes a transcript `skill_load` record;
  - prints a status line.
- `/help` gains `/skills [name]`.

### Context building

`ContextBuilder` gains:

```java
void appendSystem(String content);
```

`FullContextBuilder` appends a `Role.SYSTEM` `ChatMessage`. Existing behavior
(`start`, user/assistant/tool messages) is unchanged.

### Transcripts

`TranscriptWriter` gains:

```java
void appendSkillLoad(UUID sessionId, String name) throws IOException;
```

`FileTranscriptWriter` writes:

```json
{ "type": "skill_load", "name": "coding", "timestamp": "..." }
```

Tool-invoked skill loads are already recorded as `tool_call`/`tool_result`
records by the existing tool loop; no extra work there.

## Error handling

| Scenario | Behavior |
|---|---|
| `skill` tool with unknown name | `ToolResult("Unknown skill: X", error=true)`; loop continues |
| `/skills <unknown>` | `Unknown skill: <name>` printed; nothing loaded |
| Malformed `SKILL.md` | Skipped at discovery, warning to stderr |
| Name collision project vs global | Project wins, silently |
| `/skills <loaded>` | `Skill '<name>' is already loaded.`; no duplicate injected |

## Testing

- `SkillCatalogTest` — discovery from project + global roots; project
  precedence; malformed files skipped (missing name, missing description, bad
  name pattern, missing frontmatter); name validation; `indexText`/`render`
  formats.
- `SkillToolTest` — execute happy path returns rendered body; unknown name
  error; second call returns already-loaded note; `reset()` clears the set.
- `ToolRegistryTest` — skill tool auto-added when catalog non-empty; absent
  when empty; `resetSession` resets the skill tool.
- `FullContextBuilderTest` — `appendSystem` appends a system message without
  disturbing the rest.
- `ChatSessionTest` (extend `FakeProvider`) — system prompt contains the
  available-skills index; `/skills` lists with loaded marker; `/skills <name>`
  injects a system message and transcript record; dedupe shared between tool
  call and manual load; `/reset` clears loaded state; tools-less agent still
  gets the skill tool; unknown skill handled.
- `ConfigLoaderTest` — `projectSkillsDir`/`globalSkillsDir` parsed (relative
  resolved against the anchor, absolute used as-is); omitted keys fall back to
  the defaults.
- `AgentCatalogTest` — 7-arg constructor carries the resolved dirs; the
  convenience constructor uses the defaults.
- `ChatCommandTest` — help mentions `/skills` (REPL command, covered in
  `ChatSessionTest`; picocli `--help` unchanged).
