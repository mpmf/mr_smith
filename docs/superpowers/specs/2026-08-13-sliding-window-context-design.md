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
- Make the strategy selectable via config/env/CLI with a global default that
  each agent can override, plus a configurable window ratio (fraction of the
  agent's context limit).
- Apply the same strategy to sub-agents.

## Non-goals (this iteration)

- Summarization/compaction of old turns.
- Changing the near-limit warnings (`warnIfNearLimit`) that are based on
  cumulative session usage — see "Known interaction" below.
- Per-agent window ratio (the ratio is global; only the strategy is per-agent).
- Cost tracking.

## Decisions

- **Strategy is per-agent with a global default.**
  - Global (top-level) `contextBuilder`: `"full"` (default) | `"sliding"`. This
    is the default applied to every agent.
  - Per-agent `contextBuilder`: optional override; when set, it takes precedence
    for that agent. Effective strategy = agent's value, else the global default.
  - Env/CLI set the **global default**: `MRSMITH_CONTEXT_BUILDER`,
    `--context-builder <full|sliding>`.
  - Precedence for the global default: CLI > env > file > `"full"`.
- **Window ratio is global.** `contextWindowRatio`: double, default `0.75`
  (fraction of the agent's context limit to use as the window).
  - Env/CLI: `MRSMITH_CONTEXT_WINDOW_RATIO`, `--context-window-ratio <0..1>`.
  - Precedence: CLI > env > file > `0.75`.
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
- **Selection plumbing:** a `ContextBuilderFactory` (mirroring the existing
  `ProviderFactory`/`ToolRegistryFactory` seams) maps an `AgentRuntime` to a
  builder. `ChatSession` recreates the builder whenever the agent changes.

## Architecture

New types:

| Type | Package | Responsibility |
|---|---|---|
| `ContextStrategy` (enum) | `config` | `FULL`, `SLIDING`, with case-insensitive `parse(String)` |
| `ContextBuilderFactory` | `chat` | `ContextBuilder create(AgentRuntime)` functional seam |
| `SlidingWindowContextBuilder` | `chat` | Bounded window: pinned system messages + most recent turns within budget |
| `ContextBuilders` | `chat` | `create(AgentRuntime)` returns the right builder from the agent's effective strategy; `windowBudget(AgentRuntime)` computes the budget (default-budget + ratio handling) |

Changed types:

| Type | Change |
|---|---|
| `ContextBuilder` | `start` gains a budget: `void start(String prompt, int windowBudgetTokens)`; `default start(String)` delegates with `0` (backward compatible) |
| `FullContextBuilder` | implements `start(String, int)` ignoring the budget |
| `TokenEstimator` | add `estimateMessageTokens(ChatMessage)` |
| `OpenAiCompatibleProvider` | `estimateUsage` reuses `estimateMessageTokens` for prompt estimate |
| `AgentConfig` | add `contextBuilder` (`ContextStrategy`, effective, resolved at load) |
| `AgentRuntime.Globals` | add `contextWindowRatio` |
| `AgentCatalog` | add `contextWindowRatio` field + accessor |
| `ConfigLoader` | parse global default `contextBuilder` + `contextWindowRatio` (CLI > env > file > defaults); bake effective per-agent `contextBuilder` into each `AgentConfig`; validate ratio `(0, 1]` and strategy values |
| `CliConfig` | add `contextBuilder`, `contextWindowRatio` |
| `ChatCommand` | `--context-builder`, `--context-window-ratio` flags; inject `ContextBuilders::create` |
| `ChatSession` | hold a `ContextBuilderFactory` (replaces the injected `ContextBuilder`); recreate the builder in `startFreshSession()` from `runtime`; call `start(prompt, ContextBuilders.windowBudget(runtime))` |
| `SubAgentRunner` | use `ContextBuilders.create(config)` (change `FullContextBuilder` refs to `ContextBuilder`); pass budget at `start` |

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
    ContextBuilderFactory factory = ContextBuilders::create
    ChatSession(io, transcripts, factory, ...)

ChatSession.startFreshSession():                              // start, /reset, /agent
    contextBuilder = contextBuilderFactory.create(runtime)    // full or sliding, per agent
    contextBuilder.start(composeSystemPrompt(...), ContextBuilders.windowBudget(runtime))

turn loop:
    contextBuilder.appendUser(line)
    ToolLoop.run(contextBuilder, ...) → provider.send(contextBuilder.messages(), ...)
        tool rounds call contextBuilder.appendAssistantToolCalls / appendToolResult
    contextBuilder.appendAssistant(reply.content())

SubAgentRunner.run(...):
    contextBuilder = ContextBuilders.create(config)
    contextBuilder.start(config.agent().systemPrompt(), ContextBuilders.windowBudget(config))
    ... ToolLoop.run(contextBuilder, ...)
```

## Error Handling & edge cases

| Scenario | Behavior |
|---|---|
| `maxContextTokens` unset or ≤ 0 | Use `DEFAULT_BUDGET` (100_000) |
| Agent has no `contextBuilder` override | Uses the global default |
| Global default unset | `"full"` |
| System messages alone exceed budget | Keep them (pinned); no turns dropped |
| Current turn alone exceeds budget | Keep it intact |
| Under budget | Accumulate exactly like `FullContextBuilder` |
| `/reset` / agent switch | Builder recreated; `start(...)` re-seeds and resets the window |
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
- `ConfigLoaderTest` — global default `contextBuilder` and `contextWindowRatio`
  parsing, per-agent override resolution, defaults, precedence, invalid-value
  rejection.
- `ChatCommandTest` — `--context-builder`/`--context-window-ratio` flags.
- `ChatSessionTest` — builder recreated from the factory on agent switch; budget
  passed to `start` (via a recording stub builder/factory).
- `SubAgentRunnerTest` — sliding builder used when the agent's strategy is
  `sliding`.
