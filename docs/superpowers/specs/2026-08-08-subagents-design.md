# Design: Sub-Agents (task tool)

Date: 2026-08-08

## Problem

Mr Smith's agents can call tools, but every tool runs against the same
conversation context. There is no way to dispatch a separate, isolated agent
that works autonomously on a delegated task and returns its result — the
"agent-within-agent" primitive every capable harness has (mirroring the host
`task` tool). Large multi-step work, codebase exploration, or parallelizable
research must happen inline in the main context, bloating it.

## Goal

- A `task` built-in tool that dispatches a sub-agent with a fresh, isolated
  context seeded with the task prompt.
- The sub-agent runs its own nested tool loop (same tools minus `task`, same
  approval prompts) and returns its final answer as the tool result.
- Optional `agent` argument to select a different configured agent (default:
  current agent).
- Optional `task_id` argument to resume a prior sub-agent's conversation.
- Sub-agent transcripts persisted as flat files in the session folder
  (`subagent-<n>.jsonl`), sequential per session.

## Scope

- `TaskTool` (always available, not in `builtinNames`), a `TaskRunner`
  interface, and a `SubAgentRunner` implementation with the nested loop.
- Sub-agent transcripts: flat files per sub-agent in the session folder.
- Resume via `task_id`.
- Shared tool-loop logic extracted for reuse by the main session and sub-agent
  runner.
- Usage from sub-agent sends accumulates in the main tracker.
- The tool round limit becomes a per-agent configuration setting (currently
  hard-coded to 8 in `ChatSession`).

## Configurable tool round limit

The tool loop cap (default 32) is promoted to an agent configuration setting:

- `AgentConfig` gains `Integer maxToolRounds` (optional). `ConfigLoader`
  parses a per-agent `maxToolRounds` integer; `AgentCatalog` validates it is
  positive when present. `AppConfig` carries it.
- Omitted → default 32 (today's behavior unchanged).
- When the cap is reached, the loop (shared by the main session and sub-agents)
  prompts the user to continue with `maxToolRounds` more rounds; on decline it
  injects `Tool round limit (<n>) reached; answer without more tool calls.`
  The `DEFAULT_MAX_TOOL_ROUNDS` constant is the fallback.
- The sub-agent's nested loop uses the chosen agent's `maxToolRounds`
  (resolved from the agent the `task` call selects), so each agent can have
  its own limit.

Example:

```json
{
  "agents": [{
    "name": "coder",
    "provider": "opencode",
    "model": "deepseek-v4-flash",
    "tools": ["shell", "read_file"],
    "maxToolRounds": 12
  }]
}
```

## Non-Goals

- No parallel sub-agent dispatch (tools run sequentially, as today).
- No sub-agent model override beyond `agent` selection.
- No recursive sub-agents (the sub-agent's registry excludes `task`).
- No sub-agent context isolation from the filesystem (same CWD, same
  containment rules — a sub-agent's `edit`/`shell` are gated by the user).

## Architecture

### TaskTool

Name: `task`. Always available: `ToolRegistry.with` adds `new TaskTool(runner)`
like the other always-on tools. Not in `builtinNames()`. Marked read-only (no
approval to spawn; the sub-agent's destructive calls prompt the user).

Parameters:

```json
{ "type": "object",
  "properties": {
    "description": { "type": "string" },
    "prompt": { "type": "string" },
    "agent": { "type": "string" },
    "task_id": { "type": "string" }
  },
  "required": ["description", "prompt"] }
```

`execute`:

- Missing/blank `description` or `prompt` → `ToolException`.
- Calls `runner.run(prompt, agentName, taskId)`.
- Success → `ToolResult("Subagent subagent-<n>: <final message>", error=false)`
  — the id is embedded so the model can pass it as `task_id` to resume.
- Runner returns an error (unknown agent, unknown task_id, provider failure) →
  `ToolResult(<message>, error=true)`.

The `description()` carries the behavioral rules (do not duplicate the
sub-agent's work; be specific; state whether to write code or do research; the
sub-agent's output should generally be trusted).

`TaskTool` is stateless — it delegates to the runner. The per-session sub-agent
counter lives in `SubAgentRunner` (below).

### TaskRunner

```java
public record TaskResult(String id, String message, boolean error) {}

public interface TaskRunner {
    TaskResult run(String prompt, String agentName, String taskId);
}
```

### SubAgentRunner

`SubAgentRunner implements TaskRunner`, constructed by `ChatSession` in
`applyAgent()` with:

- `AgentCatalog` (resolve the chosen agent's config; unknown name → error)
- `ProviderFactory` (build the sub-agent's provider)
- `SkillCatalog` (build the sub-agent's tool registry)
- `IO` (approval prompts, status lines)
- `TranscriptWriter` (the main session writer — its session folder hosts the
  sub-agent files)
- `UsageTracker` (accumulate sub-agent usage)
- `Supplier<AppConfig>` — the current agent's config (default when `agent`
  omitted); a lambda over `ChatSession.config`, so `/agent` switches apply
- `Supplier<UUID>` — the current session id; a lambda over
  `ChatSession.currentSessionId`, so `/reset` picks the new folder

### The nested loop

`run(prompt, agentName, taskId)`:

1. **Resolve agent + id.** `config` = the named agent's resolved `AppConfig`
   (or the current one). `n` = the next sequential number for a fresh run, or
   parsed from `task_id` for a resume. Unknown agent → error result. Resume
   with a missing/unreadable `subagent-<n>.jsonl` → error result.
2. **Build context.** A fresh `FullContextBuilder`:
   - fresh: `start(config.systemPrompt())` then `appendUser(prompt)`;
   - resume: `start(config.systemPrompt())`, replay the transcript records,
     then `appendUser(prompt)`.
3. **Provider + tools.** `provider = providerFactory.create(config)`;
   `tools = ToolRegistry.with(config.tools(), skills, io)` **minus the `task`
   tool**.
4. **Nested loop** (shared helper, see below): send, feed tool results, cap at
   the sub-agent's agent `maxToolRounds` rounds, status lines, same
   `confirm()` approval, `ToolException` → error result.
5. **Usage.** Every sub-agent send's usage is accumulated into the main
   `UsageTracker` (`recordTurn`), so `/usage` and the context warnings reflect
   the real cost.
6. **Transcript.** Each record (user prompt, assistant, tool_call, tool_result,
   skill_load) is written to `subagent-<n>.jsonl`; resume appends.
7. **Return.** `TaskResult("subagent-" + n, finalMessage, false)`.

**Shared loop extraction.** The existing `runToolLoop` in `ChatSession` is
extracted into a small helper (e.g. `ToolLoop`) parameterized by
`contextBuilder`, `provider`, `tools`, `io`, `transcriptWriter`, a usage
recorder, and a status-line sink. Both `ChatSession` and `SubAgentRunner`
delegate to it. The main loop's behavior (history, round limit, approval,
transcripts) is preserved.

**Resume replay.** A `SubAgentTranscriptStore` reads `subagent-<n>.jsonl`
back into `ChatMessage`s:

| record | replay as |
|---|---|
| `user` | `ChatMessage(USER, content)` |
| `assistant` | `ChatMessage(ASSISTANT, content, thinking)` |
| `tool_call` | `ChatMessage(ASSISTANT, null, toolCalls=[ToolCall(id, name, arguments)])` |
| `tool_result` | `ChatMessage(TOOL, content, toolCallId=id)` |
| `skill_load` | skipped (sub-agents load skills via the `skill` tool, which is captured as `tool_call`/`tool_result`) |

### Transcript persistence

- Main session: unchanged (`<sessionFolder>/transcript.jsonl`). The task call
  and its result already appear as `tool_call`/`tool_result` records in the
  main transcript via the normal tool loop.
- Sub-agents: flat files `<sessionFolder>/subagent-1.jsonl`,
  `subagent-2.jsonl`, … — one per fresh task call, sequential per session.
  Numbering starts at 1 for each session folder (i.e., resets on `/reset` and
  `/agent`). A resumed run appends to the existing file.
- Implementation: the JSONL record-building in `FileTranscriptWriter` is
  extracted into a shared base class parameterized by the target file.
  `SubAgentTranscriptWriter extends` it and targets `subagent-<n>.jsonl`; its
  `start()` is a no-op (the parent session folder already exists).
  `SubAgentTranscriptStore` locates/reads the file for resume.

### Registry & factory

- `ToolRegistryFactory` becomes `ToolRegistry create(AppConfig config,
  SkillCatalog catalog, IO io, TaskRunner taskRunner)`.
- `ToolRegistry.with(List<String>, SkillCatalog, IO, TaskRunner)` always adds
  `new TaskTool(taskRunner)` alongside the other always-on tools.
- `ChatSession.applyAgent()` builds a fresh `SubAgentRunner` (with suppliers
  over its own `config`/`currentSessionId`) and passes it to the factory.

## Error handling

| Scenario | Behavior |
|---|---|
| `task` missing/blank `description` or `prompt` | `ToolException` |
| `task` unknown `agent` | error result (`Unknown agent: <name>`) |
| `task` unknown/missing `task_id` file | error result (`Unknown task_id: <id>`) |
| Sub-agent provider error | error result with the message; main turn continues |
| Sub-agent round limit | prompts the user to continue, like the main loop, capped at the agent's `maxToolRounds` |
| Sub-agent destructive call declined | declined tool result; loop continues |

## Testing

- `TaskToolTest` — validates args; delegates to the runner; formats
  `Subagent subagent-<n>: <message>`; unknown agent / task_id error results;
  `reset()` clears the counter.
- `SubAgentRunnerTest` (extend `FakeProvider`) — fresh run seeds
  system+prompt and returns the final message; writes `subagent-1.jsonl` with
  user/assistant/tool records; resume replays prior records then appends the
  new prompt and continues; sub-agent `edit` prompts for approval; usage
  accumulates; honors the agent's `maxToolRounds`.
- `SubAgentTranscriptStoreTest` — write/read round-trip (user, assistant,
  tool_call, tool_result); sequential numbering restarting per session.
- `ToolRegistryTest` — `task` always added (empty catalog → 4 tools: edit,
  todowrite, question, task; +skill when catalog non-empty), not in
  `builtinNames()`, factory carries the runner.
- `ConfigLoaderTest`/`AgentCatalogTest` — `maxToolRounds` parsed per agent;
  omitted defaults to 8; non-positive values rejected.
- `ChatSessionTest` — model calls `task` → sub-agent runs (fake runner) and
  the result feeds back as a `role:tool` message; tools-less agent gets
  `task`; `/reset` restarts the sub-agent counter; the round limit honors a
  configured `maxToolRounds`.
