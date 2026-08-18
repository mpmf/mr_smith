# Mr Smith — Reasoning Effort Design

Date: 2026-08-18
Status: Approved

## Context

Many OpenAI-compatible endpoints accept a `reasoning_effort` request parameter
(e.g. `low`, `medium`, `high`, and for some models `minimal`) that controls how
much computation a reasoning model spends on a response. Mr Smith currently
does not send this parameter, so reasoning models always run at their default
effort.

This design lets each agent opt into a specific `reasoning_effort`, which is
passed through to the provider on every `/chat/completions` request.

## Goals

- Allow a per-agent `reasoningEffort` string in the config file.
- Emit it as the `reasoning_effort` request-body field on every request when
  set.
- Emit nothing when unset, so providers/models that do not support the
  parameter are unaffected.

## Non-goals (this iteration)

- Provider-level or global defaults for `reasoningEffort`.
- CLI flags or environment variables (`MRSMITH_REASONING_EFFORT`, etc.).
- Value validation: any non-blank string is passed through unchanged.
- Per-request/turn override at runtime.

## Decisions

- **Agent-level, config-file only.** `reasoningEffort` is an optional string on
  each agent. No env/CLI precedence; the config file is the sole source.
- **Pass-through semantics.** The value is sent verbatim. No whitelist or enum,
  since the accepted values differ across providers and models.
- **Blank is unset.** An empty or whitespace-only value is treated as if the
  field were absent, and no `reasoning_effort` is emitted.
- **Optional emission.** The request body includes `reasoning_effort` only when
  the effective value is non-blank; when absent, the field is omitted entirely.

## Architecture

Changed types:

| Type | Change |
|---|---|
| `AgentConfig` | Add a nullable `reasoningEffort` (`String`) component |
| `ConfigLoader` | Parse `agents[].reasoningEffort` via `asText(null)` |
| `OpenAiCompatibleProvider` | Emit `reasoning_effort` in `buildRequestBody` when the agent's value is non-blank |

No new types are introduced.

## Request body

In `OpenAiCompatibleProvider.buildRequestBody`, after setting `model` and
`stream`:

```java
String effort = runtime.agent().reasoningEffort();
if (effort != null && !effort.isBlank()) {
    root.put("reasoning_effort", effort);
}
```

## Data Flow

```
config.json → ConfigLoader.parseAgents → AgentConfig.reasoningEffort
    → AgentRuntime.agent() → OpenAiCompatibleProvider.buildRequestBody
        → "reasoning_effort": "<value>" (only when non-blank)
```

## Error handling & edge cases

| Scenario | Behavior |
|---|---|
| `reasoningEffort` unset | No `reasoning_effort` field in the request body |
| `reasoningEffort` empty or whitespace-only | Treated as unset |
| Any non-blank value (`low`, `high`, custom, …) | Passed through verbatim |
| Provider/model does not support the parameter | Unaffected when unset; if set, the provider responds per its own validation (no client-side guard) |
| Sub-agents | Inherit the parent agent's value automatically (same `AgentConfig`/`AgentRuntime` path); a sub-agent selecting a different agent uses that agent's value |

## Testing

- `OpenAiCompatibleProviderTest` (MockWebServer) — request body contains
  `reasoning_effort` when the agent sets it, and omits it when the agent's value
  is null or blank.
- `ConfigLoaderTest` — `reasoningEffort` is parsed into `AgentConfig` when
  present, and is null when absent or blank.
- `AgentConfig` — constructor coverage for the new component (via existing
  overloads/defaults).
