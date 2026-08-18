# Mr Smith — Runtime Reasoning Effort Override Design

Date: 2026-08-18
Status: Approved

## Context

The previous design added a per-agent `reasoningEffort` config field that is
emitted as `reasoning_effort` on every request. It is fixed at startup from the
config file. This design adds a REPL command, `/reasoning`, that overrides the
configured value at runtime for the current session.

## Goals

- Add a `/reasoning` command to set, show, and clear a runtime override.
- The override takes effect on subsequent requests for the current agent.
- The override is session-scoped: cleared by `/reset` and by an agent switch.
- Sub-agents inherit the override only when they use the current agent; a
  named sub-agent (with its own config) uses its own configured value.

## Non-goals (this iteration)

- Persisting the override to the config file or across restarts.
- Per-agent override CLI flags or environment variables.
- Value validation: any non-blank token is accepted (consistent with the
  config field's pass-through semantics), except the reserved `off` token.

## Decisions

- **Command syntax.**
  - `/reasoning` — show the current effective value, annotated with its source
    (override vs. config) or "not set".
  - `/reasoning off` — clear the override (fall back to the configured value).
  - `/reasoning <value>` — set the override to `<value>` (any non-blank token).
- **Mutable holder.** A new mutable `ReasoningEffort` type holds the override.
  It is not a record, because the override is deliberately mutable session state.
- **Holder lives on `AgentRuntime`.** `AgentRuntime` gains a 4th component
  `ReasoningEffort reasoning`. A convenience 3-arg constructor (used by existing
  call sites/tests) defaults to `new ReasoningEffort()`. `AgentCatalog.resolve()`
  constructs a fresh holder on every call.
- **Effective value.** `AgentRuntime.effectiveReasoningEffort()` returns
  `reasoning.effective(agent.reasoningEffort())` — the override when set,
  otherwise the configured value. The provider reads this method instead of
  `agent.reasoningEffort()` directly.
- **Session scoping.** `ChatSession.startFreshSession()` clears the holder
  (`runtime.reasoning().clear()`). Agent switches create a fresh runtime (and
  thus a fresh holder) via `applyAgent()`.
- **Sub-agent inheritance falls out of the object graph.**
  - Same-agent sub-agent: `SubAgentRunner.resolveConfig(null)` returns
    `currentConfig.get()`, i.e. the shared `runtime` object, so it sees the
    override.
  - Named sub-agent: `agents.resolve(name)` returns a fresh runtime with a fresh
    (empty) holder, so its own configured value wins.

## Architecture

New types:

| Type | Package | Responsibility |
|---|---|---|
| `ReasoningEffort` | `config` | Mutable override holder: `set`, `clear`, `override`, `isSet`, `effective(String configured)` |

Changed types:

| Type | Change |
|---|---|
| `AgentRuntime` | Add 4th component `ReasoningEffort reasoning`; convenience 3-arg constructor; `effectiveReasoningEffort()` helper |
| `AgentCatalog` | `resolve` passes a fresh `ReasoningEffort` (already covered by the convenience constructor) |
| `OpenAiCompatibleProvider` | `buildRequestBody` reads `runtime.effectiveReasoningEffort()` |
| `ChatSession` | `/reasoning` command handler; `startFreshSession()` clears the holder; `/help` text updated |

## `ReasoningEffort`

```java
public final class ReasoningEffort {
    private String override;

    public void set(String value) {
        this.override = value;
    }

    public void clear() {
        this.override = null;
    }

    public String override() {
        return override;
    }

    public boolean isSet() {
        return override != null && !override.isBlank();
    }

    public String effective(String configured) {
        return isSet() ? override : configured;
    }
}
```

## Command behavior

| Input | Behavior |
|---|---|
| `/reasoning` | Print the effective value. If an override is set, show it and note the configured value; if only configured, show it and note "from config"; if neither, "not set". |
| `/reasoning off` | Clear the override; print confirmation that the configured value (or "not set") is now in effect. |
| `/reasoning high` (or any non-blank, non-`off` token) | Set the override; print confirmation. |

## Error handling & edge cases

| Scenario | Behavior |
|---|---|
| `/reasoning` with neither config nor override | Prints "not set" |
| `/reasoning off` with no override | Prints confirmation; effective value unchanged (config, or "not set") |
| `/reasoning <value>` then `/reset` | Override cleared in `startFreshSession()` |
| `/reasoning <value>` then `/agent b` | New runtime → new holder → override gone |
| Same-agent sub-agent after `/reasoning <value>` | Inherits override (shared runtime) |
| Named sub-agent after `/reasoning <value>` | Uses its own configured value (fresh runtime) |
| Blank token after `/reasoning ` (e.g. `/reasoning ` with trailing space) | Treated as "show" |

## Testing

- `ReasoningEffortTest` — `set`/`clear`/`override`/`isSet`/`effective`
  (override wins when set; configured returned when not set or blank).
- `AgentRuntimeTest` — `effectiveReasoningEffort()` precedence; convenience
  constructor defaults to an empty holder.
- `OpenAiCompatibleProviderTest` — request body emits `reasoning_effort` from
  the override when set; falls back to the configured value otherwise; omits
  when both are null/blank. (Existing emission tests remain green.)
- `ChatSessionTest` — `/reasoning` set/show/clear output; override cleared on
  `/reset` and on `/agent` switch.
- `SubAgentRunnerTest` — same-agent sub-agent inherits the override; named
  sub-agent does not.
