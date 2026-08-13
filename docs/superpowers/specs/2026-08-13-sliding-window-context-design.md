# Mr Smith — Sliding-Window Context Builder Design

Date: 2026-08-13
Status: Approved

## Context

Today `ContextBuilder` has a single implementation, `FullContextBuilder`, which
accumulates every message and returns the entire conversation. This is the
"full accumulation" strategy anticipated by the history/context separation
design. This design adds a second strategy: a **sliding window** that keeps only
the most recent messages that fit within a token budget, dropping older turns.

History still grows without bound (it remains the full conversation record,
written to the transcript, including thinking); the sliding window bounds only
the **context** sent to the provider.

## Goals

- Add a `SlidingWindowContextBuilder` that bounds context to a token budget,
  keeping the most recent turns.
- Keep system messages (system prompt + `/skills` loads) pinned and never
  dropped.
- Keep assistant tool-call messages and their tool-result messages together as
  one atomic unit; never drop one without the other.
- Make the strategy selectable via config/env/CLI, with a configurable window
  ratio (fraction of the agent's context limit).
- Apply the same strategy to sub-agents.

## Non-goals (this iteration)

- Summarization/compaction of old turns.
- Changing the near-limit warnings (`warnIfNearLimit`) that are based on
  cumulative session usage — see "Known interaction" below.
- Per-agent strategy selection (strategy is global, like `includeUsage`).
- Cost tracking.

## Decisions

- **Strategy and ratio are global** settings, mirroring `includeUsage`. The
  budget's `maxContextTokens` remains per-agent.
  - `contextBuilder`: `"full"` (default) | `"sliding"`.
  - `contextWindowRatio`: double, default `0.75` (fraction of the agent's
    context limit to use as the window).
  - Env vars: `MRSMITH_CONTEXT_BUILDER`, `MRSMITH_CONTEXT_WINDOW_RATIO`.
  - CLI flags: `--context-builder <full|sliding>`, `--context-window-ratio <0..1>`.
  - Precedence: CLI > env > file > defaults (as with `sessionsDir`).
- **Budget:** `round((maxContextTokens > 0 ? maxContextTokens : DEFAULT_BUDGET) * ratio)`.
  `DEFAULT_BUDGET = 100_000` is used when `maxContextTokens` is unset or ≤ 0.
- **Atomic unit is the turn:** a `USER` message through every following
  `ASSISTANT`/`TOOL` message up to (not including) the next `USER` message.
  Trimming drops the oldest complete turn only.
- **Trim-on-append:** the window is trimmed incrementally after each
  non-system append, always from the front.
- **Estimation:** per-message token estimation (content + tool call
  id/name/arguments + tool-call id for results, plus small fixed overheads),
  reusing the existing `TokenEstimator` heuristic.
- **Selection plumbing:** `ChatCommand` picks the builder type from the global
  strategy; `ChatSession` passes the per-agent budget to `start(...)`.

## Architecture

New types:

| Type | Package | Responsibility |
|---|---|---|
| `ContextStrategy` (enum) | `config` | `FULL`, `SLIDING`, with case-insensitive `parse(String)` |
| `SlidingWindowContextBuilder` | `chat` | Bounded window: pinned system messages + most recent turns within budget |
| `ContextBuilders` | `chat` | `create(ContextStrategy)` returns the right builder; `windowBudget(AgentRuntime)` computes the budget (default-budget + ratio handling) |

Changed types:

| Type | Change |
|---|---|
| `ContextBuilder` | `start` gains a budget: `void start(String prompt, int windowBudgetTokens)`; `default start(String)` delegates with `0` (backward compatible) |
| `FullContextBuilder` | implements `start(String, int)` ignoring the budget |
| `TokenEstimator` | add `estimateMessageTokens(ChatMessage)` |
| `OpenAiCompatibleProvider` | `estimateUsage` reuses `estimateMessageTokens` for prompt estimate |
| `AgentConfig` | unchanged (budget stays per-agent via `maxContextTokens`) |
| `AgentRuntime.Globals` | add `contextStrategy`, `contextWindowRatio` |
| `AgentCatalog` | add `contextStrategy`, `contextWindowRatio` fields + accessors |
| `ConfigLoader` | parse `contextBuilder`, `contextWindowRatio` (CLI > env > file > defaults); validate ratio `(0, 1]` and strategy value |
| `CliConfig` | add `contextBuilder`, `contextWindowRatio` |
| `ChatCommand` | `--context-builder`, `--context-window-ratio` flags; pick builder via `ContextBuilders.create(catalog.contextStrategy())` |
| `ChatSession` | `startFreshSession()` calls `contextBuilder.start(prompt, ContextBuilders.windowBudget(runtime))` |
| `SubAgentRunner` | build the right builder from `config.globals().contextStrategy()`; pass budget at `start` |

## `SlidingWindowContextBuilder` algorithm

State:

- `system`: pinned `SYSTEM` messages (system prompt + `/skills` loads), in order.
- `turns`: all non-system messages, in order, further subdivided into turns by
  `USER` boundaries.
- Running estimated-token totals for `system` and `turns`.

Append behavior:

- `start(prompt, budget)` — clear both sections; store budget; seed `system`
  with the prompt when non-null.
- `appendSystem(content)` — add to `system` (never trimmed).
- `appendUser(content)` — add `USER` to `turns`; trim.
- `appendAssistant(content)` / `appendAssistantToolCalls(...)` /
  `appendToolResult(...)` — add message to `turns`; trim.

Trim (after each non-system append):

```
while (systemTokens + turnTokens) > budget and turns has ≥2 turns:
    drop the oldest complete turn from the front; subtract its tokens
```

The current (last) turn is never dropped, even if it alone exceeds the budget.
System messages are never dropped, even if they alone exceed the budget.

`messages()` returns an immutable snapshot of `system` + `turns`.

A `windowBudgetTokens ≤ 0` passed to `start` is treated as `DEFAULT_BUDGET` by
the sliding builder (defensive; production always passes a real budget).

## Data Flow (one session)

```
ChatCommand:
    builder = ContextBuilders.create(catalog.contextStrategy())   // full or sliding
    ChatSession(io, transcripts, builder, ...)

ChatSession.startFreshSession():                                  // start, /reset, /agent
    builder.start(composeSystemPrompt(...), ContextBuilders.windowBudget(runtime))

turn loop:
    builder.appendUser(line)
    ToolLoop.run(builder, ...) → provider.send(builder.messages(), ...)
        tool rounds call builder.appendAssistantToolCalls / appendToolResult
    builder.appendAssistant(reply.content())

SubAgentRunner.run(...):
    builder = ContextBuilders.create(config.globals().contextStrategy())
    builder.start(config.agent().systemPrompt(), ContextBuilders.windowBudget(config))
    ... ToolLoop.run(builder, ...)
```

## Error Handling & edge cases

| Scenario | Behavior |
|---|---|
| `maxContextTokens` unset or ≤ 0 | Use `DEFAULT_BUDGET` (100_000) |
| System messages alone exceed budget | Keep them (pinned); no turns dropped |
| Current turn alone exceeds budget | Keep it intact |
| Under budget | Accumulate exactly like `FullContextBuilder` |
| `/reset` / agent switch | `start(...)` re-seeds and resets the window |
| Invalid `contextBuilder` / `contextWindowRatio` | `ConfigException` at load |
| Tool call without its result (window boundary) | Cannot happen — turn is atomic |

## Known interaction (out of scope, noted for follow-up)

`ChatSession.warnIfNearLimit()` warns at 85%/100% of `maxContextTokens` based on
**cumulative** session usage. When the sliding window is active the sent context
is already bounded, so those warnings become misleading. This iteration leaves
them unchanged; a follow-up may suppress or reword them when sliding is active.

## Testing

- `SlidingWindowContextBuilderTest` — system prompt seeded; system messages
  pinned; drops oldest turn when over budget; keeps call+result pairs atomic;
  current turn never dropped even when alone over budget; under-budget
  accumulation equals full; `start` resets; `messages()` immutable.
- `TokenEstimatorTest` — `estimateMessageTokens` for plain, tool-call, and
  tool-result messages.
- `ConfigLoaderTest` — `contextBuilder`/`contextWindowRatio` parsing, defaults,
  precedence, invalid-value rejection.
- `ChatCommandTest` — `--context-builder`/`--context-window-ratio` flags.
- `ChatSessionTest` — budget passed to `start` (via a recording stub builder).
- `SubAgentRunnerTest` — sliding builder used when strategy is `sliding`.
