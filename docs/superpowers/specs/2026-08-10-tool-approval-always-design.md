# Design: "Always Allow" Tool Approval Option

Date: 2026-08-10

## Problem

Before running a non-read-only tool (e.g. `shell`, `write_file`) the session
prompts `Run <name>(<args>) [y/N]?` and accepts `y`/`yes` or declines. For
multi-call workflows the same tool is re-prompted on every call across the
session, forcing the user to answer the same yes repeatedly. There is no way to
say "yes, and don't ask me again this session".

## Goal

Add a third option to the tool approval prompt: **always allow**. When chosen,
that specific tool runs without prompting for the rest of the session. The
option appears in the main session loop and sub-agent loops alike (they share
`ToolLoop`), and is cleared on `/reset` and agent switch (i.e. it is
session-scoped, like `ToolBudget`).

## Scope

- The approval prompt in `ToolLoop` gains an `a` option (`always`).
- A session-scoped `ToolApproval` object records tool names approved for the
  session; it is passed into `ToolLoop.run` and shared with sub-agents.
- `ChatSession` creates/resets the `ToolApproval` in `startFreshSession()`.
- `SubAgentRunner` receives the same `ToolApproval` instance so an always-allow
  decision applies to sub-agent tool calls too.
- Prompt input accepts `a`/`always` (case-insensitive), in addition to the
  existing `y`/`yes`.

## Non-Goals

- No change to the `web_fetch` private-host approval prompt (`WebFetchTool`,
  per-host, scoped to a single fetch chain). It stays `[y/N]`.
- No change to the round-limit continue prompt (`[y/N]`) or the session
  tool-budget behavior/message.
- No "never/always-deny" option.
- No persistence across processes — the approval lasts only until the session
  is reset or the process exits.
- No per-argument or per-command specificity; "always" applies to the tool
  name as a whole.

## Architecture

### `ToolApproval` (new)

`com.mrsmith.chat.ToolApproval` — a plain session-scoped holder:

```java
public final class ToolApproval {

    private final Set<String> alwaysAllowed = new HashSet<>();

    public boolean isAlwaysAllowed(String toolName) { ... }

    public void allowAlways(String toolName) { ... }

    public void reset() { ... }
}
```

It mirrors `ToolBudget`'s role: session state owned by `ChatSession` and
injected into the tool loop.

### Prompt

The approval prompt in `ToolLoop` changes from `[y/N]` to `[y/N/a=always]`:

```
Run <name>(<args>) [y/N/a=always]? 
```

Input handling:
- `y`/`yes` → allow this call only.
- `a`/`always` → allow this call and remember the tool name for the session.
- anything else / `null` (EOF) / `IOException` → decline (unchanged).

### `ToolLoop`

`confirm` becomes tri-state. Introduce a small private enum:

```java
private enum ConfirmDecision { ALLOW, ALWAYS_ALLOW, DECLINE }
```

- `confirm(call, tool, io)` returns `ALLOW`, `ALWAYS_ALLOW`, or `DECLINE`.
- `executeTool` gains the `ToolApproval` parameter. Before prompting, if
  `approval.isAlwaysAllowed(tool.name())` the prompt is skipped and the tool
  runs. Otherwise it prompts and, on `ALWAYS_ALLOW`, records the name via
  `approval.allowAlways(tool.name())`.
- `run(...)` gains a trailing `ToolApproval approval` parameter threaded into
  each `executeTool` call.

### `ChatSession`

- New field `private ToolApproval toolApproval;` (initialized eagerly so the
  sub-agent supplier is never null).
- `startFreshSession()` calls `toolApproval.reset()` so `/reset` and agent
  switch clear approvals.
- `runToolLoop()` passes `toolApproval` into `ToolLoop.run`.
- `applyAgent()` passes `() -> toolApproval` into the `SubAgentRunner.Context`
  so sub-agents share the same instance.

### `SubAgentRunner`

- `Context` gains `Supplier<ToolApproval> approval`.
- `run()` passes `approval.get()` into `ToolLoop.run`.

## Error handling

| Scenario | Behavior |
|---|---|
| Tool already always-allowed | prompt skipped; tool runs |
| User answers `y`/`yes` | runs once; not remembered |
| User answers `a`/`always` | runs; tool name remembered for the session |
| User answers anything else / EOF / `IOException` | declined (existing) |
| `/reset` or `/agent <name>` | approvals cleared |

## Testing

- `ChatSessionTest`:
  - `alwaysAllowsToolWithoutReprompting` — non-read-only tool called
    repeatedly; `a` at the first prompt; tool executes on every call with a
    single prompt total; no decline result.
  - `alwaysAllowClearedOnReset` — `a` approves; `/reset`; same tool prompts
    again on the next call.
  - existing `declinesNonReadOnlyTool` / `confirmsNonReadOnlyToolOnYes` pass
    unchanged (`n`/`y` still work).
- `SubAgentRunnerTest`:
  - `subAgentSharesAlwaysAllowDecision` — an already-approved tool name runs
    without prompting inside a sub-agent.
  - existing `destructiveToolPromptsForApproval` /
    `declinedDestructiveToolRecordsDecline` pass unchanged.
- Full suite stays green.
