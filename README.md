# Mr Smith

Mr Smith is a lightweight, framework-free **agentic coding harness** for the
command line. It connects to any OpenAI-compatible chat endpoint, streams
responses token by token, and gives the model a toolbox — shell, file editing,
web fetch, skills, task lists, and sub-agents — so it can actually get work
done in your repository.

It is a plain interactive REPL: type a prompt, watch the model reason and
answer (or call tools), and drive the session with simple `/` commands.

---

## Features

- **Interactive chat** against any OpenAI-compatible endpoint, with live
  token-by-token streaming.
- **Reasoning display** — thinking/reasoning streams live in yellow (plain when
  piped), stored in history but never re-sent to the model.
- **Tool calling loop** — the model can invoke tools (capped at 32 rounds per
  turn by default, configurable per agent; optionally also bounded by a
  per-session tool budget), with confirmation prompts for any action that
  modifies your filesystem.
- **Multiple providers & agents** — named providers (credentials/endpoint) and
  named agents (model + system prompt + context limit + tool allowlist),
  switchable mid-session.
- **Skills** — reusable markdown instruction bundles (`SKILL.md`) discovered on
  disk and loaded on demand into the context.
- **Sub-agents** — a `task` tool that dispatches isolated sub-agents with their
  own nested tool loop, transcript, and optional resume.
- **Session persistence** — every session gets a time-ordered UUID (v7) and a
  JSONL transcript under the sessions directory.
- **Context awareness** — per-turn usage lines, a `/usage` report, and warnings
  at 85% / 100% of the configured context limit.
- **Sandboxed file tools** — all file/command activity is rooted at the CWD
  where mr-smith was launched; paths that escape it are refused.

---

## Requirements

- **Java 21+** (LTS) — the project uses records, pattern matching, and the JDK
  HTTP client.
- **Maven** 3.x to build.
- An OpenAI-compatible API endpoint and key (any provider speaking the
  `/chat/completions` streaming dialect).

---

## Build & Run

```bash
mvn -q package
java -jar target/mr-smith.jar
```

To see the CLI help:

```bash
java -jar target/mr-smith.jar --help
```

The shaded (`java -jar`-ready) artifact is `target/mr-smith.jar`.

---

## Configuration

Mr Smith reads its configuration from `~/.config/mrsmith/config.json` (create
it on first use). Providers own the credentials and endpoint; agents combine a
provider with a model, system prompt, context limit, and tool allowlist.

```json
{
  "providers": [
    { "name": "opencode", "apiKey": "sk-...", "baseUrl": "https://opencode.ai/zen/go/v1" }
  ],
  "agents": [
    {
      "name": "coder",
      "provider": "opencode",
      "model": "opencode-go/deepseek-v4-flash",
      "systemPrompt": "You are an expert software engineer working in a Java repository.",
      "maxContextTokens": 128000,
      "maxToolRounds": 32,
      "maxToolCallsPerSession": 500,
      "tools": ["shell", "read_file", "write_file", "list_dir", "glob", "web_fetch"]
    }
  ],
  "defaultAgent": "coder",
  "includeUsage": true,
  "sessionsDir": "~/.config/mrsmith/sessions",
  "projectSkillsDir": "./skills",
  "globalSkillsDir": "~/.config/mrsmith/skills"
}
```

### Fields

| Field | Meaning |
|---|---|
| `providers[].name` | Unique provider name |
| `providers[].apiKey` | API key (required) |
| `providers[].baseUrl` | Endpoint base URL, e.g. `https://api.openai.com/v1` (required) |
| `agents[].name` | Unique agent name |
| `agents[].provider` | Provider this agent uses |
| `agents[].model` | Model name (required) |
| `agents[].systemPrompt` | System prompt (optional) |
| `agents[].maxContextTokens` | Context window limit; enables limit warnings (optional) |
| `agents[].maxToolRounds` | Max tool-call rounds per turn (optional, default 32) |
| `agents[].maxToolCallsPerSession` | Max executed tool calls per session, shared with sub-agents (optional; unlimited by default). Warns at 80% and stops the loop with a graceful message when exhausted |
| `agents[].tools` | Allowlist of built-in tools (optional; `edit`, `todowrite`, `question`, `skill`, `task` are always available) |
| `defaultAgent` | Agent used at startup (required, must match an agent) |
| `includeUsage` | Send `stream_options.include_usage` for real token counts (default `true`; set `false` if your provider rejects it with a 400) |
| `sessionsDir` | Where session transcripts are stored (default `~/.config/mrsmith/sessions`) |
| `projectSkillsDir` | Project-level skills directory (default `./skills`) |
| `globalSkillsDir` | Global skills directory (default `~/.config/mrsmith/skills`) |

### Precedence

CLI flags (`--agent`, `--sessions-dir`) > environment variable
(`MRSMITH_SESSIONS_DIR`) > config file > defaults.

---

## Usage

Start a chat:

```bash
java -jar target/mr-smith.jar
java -jar target/mr-smith.jar --agent coder
```

Type anything to send it to the model. Reasoning streams in yellow, then the
answer, then a per-turn usage line. A session banner prints the session UUID —
transcripts are written to `<sessionsDir>/<uuid>/transcript.jsonl`.

### REPL commands

| Command | Description |
|---|---|
| `/exit` | Quit |
| `/reset` | Clear history and start a fresh session (new UUID, fresh context) |
| `/help` | Show commands |
| `/usage` | Show token usage, context-limit percentage, history size, and the tool-call budget |
| `/agents` | List configured agents |
| `/agent <name>` | Switch agent (starts a new session) |
| `/skills` | List discovered skills (`*` marks loaded ones) |
| `/skills <name>` | Load a skill into the context manually |
| `/tasks` | Show the session task list |

Anything else is sent to the LLM. Unknown `/` commands are rejected with a hint.

---

## Tools

The model can call tools during a turn; results are fed back so it can iterate
until it produces a final answer. **Read-only tools run automatically; anything
that modifies the filesystem prompts for confirmation** (`y/N`).

### Per-agent built-ins (opt-in via `agents[].tools`)

| Tool | Read-only | Description |
|---|---|---|
| `shell` | no | Runs `bash -c <command>` in the CWD; returns stdout, stderr, exit code (30s timeout) |
| `read_file` | yes | Reads a file (capped at 1 MiB) |
| `write_file` | no | Writes a file relative to the CWD, creating parent directories |
| `list_dir` | yes | Lists directory entries |
| `glob` | yes | Matches files under the CWD (e.g. `src/**/*.java`) |
| `web_fetch` | yes | Fetches an HTTP(S) URL and returns the body text (1 MiB cap, follows redirects) |

### Always-on tools (available to every agent)

| Tool | Read-only | Description |
|---|---|---|
| `edit` | no | Replaces an exact substring in a file; fails unless it matches exactly once (or `replaceAll` is set) |
| `todowrite` | yes | Replaces the session task list (content / status / priority); session-scoped |
| `question` | yes | Prompts you with numbered options and returns your answer(s) |
| `skill` | yes | Loads a skill body into the context (available when skills exist) |
| `task` | yes | Dispatches a sub-agent with an isolated context and returns its final answer |

All file tools resolve paths against the CWD, normalize them, and refuse paths
that escape the CWD root (including symlink escapes).

### Session tool budget

Besides the per-turn round cap, an agent can set `maxToolCallsPerSession` to
bound the total number of **executed** tool calls across the whole session —
including sub-agent tool calls, which draw from the same pool. The budget is
scoped to a session and resets on `/reset` and agent switches.

- When 80% of the budget is used, a one-time warning is printed.
- When the budget is exhausted mid-turn, the loop stops and asks the model to
  summarize and tell you to `/reset` (or send `continue`) rather than failing
  abruptly.
- `/usage` reports the current count against the limit (e.g. `tool calls: 42/500`).
- It is optional and off by default; leave it unset for unlimited calls.

---

## Skills

Skills package reusable know-how (coding conventions, release checklists,
debugging workflows) as markdown files that load into context only when needed.
Each skill is a directory containing a `SKILL.md` with YAML-style frontmatter:

```markdown
---
name: coding
description: Guidance for writing idiomatic Java in this project.
---

Always run `mvn -q test` before claiming work is done.
- Follow the existing package layout in src/main.
```

- Skill names must match `^[a-z0-9]+(-[a-z0-9]+)*$` and equal the directory name.
- Discovered from `globalSkillsDir` then `projectSkillsDir` (project wins on
  name collision).
- The system prompt always lists available skills (name + description only).
- The model loads a skill via the `skill` tool, or you load one with
  `/skills <name>`. Once loaded, the body stays in context until `/reset` or an
  agent switch.
- `resourceDir` is the skill's directory, so bundled scripts are accessible via
  `read_file` / `shell` / `glob`.

---

## Sub-Agents

The `task` tool dispatches an isolated sub-agent: a fresh context seeded with
your prompt, its own nested tool loop (same tools minus `task`), and a
persistent transcript written as `subagent-<n>.jsonl` in the session folder.

- Optional `agent` argument selects a different configured agent (default: the
  current agent).
- Optional `task_id` (e.g. `subagent-3`) resumes a prior sub-agent's
  conversation.
- Sub-agent usage accumulates in the main session tracker, and sub-agent tool
  calls count against the session tool budget when one is configured.

---

## Sessions & Transcripts

- Each session (and `/reset`, and each agent switch) starts a new time-ordered
  **UUID v7** session.
- Transcripts are JSONL files under `sessionsDir/<uuid>/transcript.jsonl`,
  recording user messages, assistant replies (with thinking), tool calls and
  results, skill loads, and per-turn token usage (real or estimated).
- Transcript writes are best-effort; a failure disables persistence for the
  session with a warning rather than crashing.

---

## Architecture

Single Maven module with disciplined package boundaries under `com.mrsmith`:

| Package | Responsibility |
|---|---|
| `cli` | `Main` entry point + picocli `ChatCommand` |
| `io` | `IO` port + `ReplIo` (stdin/stdout, colored reasoning) |
| `chat` | `ChatSession`, context builders, tool loop, sub-agent runner, usage tracking, UUID v7 |
| `provider` | `Provider` port + `OpenAiCompatibleProvider` + SSE parser, message DTOs, token estimation |
| `config` | `AgentCatalog`, `ConfigLoader` (JSON config + env + CLI), config records |
| `session` | `TranscriptWriter` port + `FileTranscriptWriter` (JSONL), sub-agent transcript store |
| `skill` | `SkillCatalog`, `SkillFrontmatter` parser |
| `tool` | `Tool` port, built-in tool implementations, `ToolRegistry`, path containment |

Key design points:

- **Ports and adapters** — `Provider`, `IO`, `TranscriptWriter`, `Tool`, and
  `ContextBuilder` are seams; new backends/adapters slot in without touching
  core logic.
- **History vs context** — full conversation history (including thinking) is
  kept separately from the incremental context window actually sent to the
  model.
- **No framework** — JDK HTTP client, Jackson, picocli, JUnit 5. Minimal
  dependencies.
- Each feature has a design spec and implementation plan under
  [`docs/superpowers/`](docs/superpowers/).

---

## Testing

```bash
mvn test
```

The suite (280+ tests) covers config loading and precedence, the chat loop,
SSE parsing, provider request serialization (via OkHttp MockWebServer),
context building, tool path containment, skill parsing/discovery, sub-agent
transcripts, and session persistence. No real API calls are made in tests.

---

## License

See the project repository for licensing details.
