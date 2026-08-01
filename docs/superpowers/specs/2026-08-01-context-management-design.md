# Mr Smith — Context Management Design

Date: 2026-08-01
Status: Approved

## Context

`ChatSession.history` grows without bound: every user message and assistant
reply is appended forever, and the user has no idea how much of the model's
context window has been consumed. This design adds context-size awareness and
manual management. Automatic compaction/summarization of old turns is
explicitly deferred to a future feature.

## Goals

- Show the user how much context is used: a per-turn usage line, a `/usage`
  command, and warnings when the session approaches the model's context limit.
- Use accurate token counts from the provider when available, falling back to
  a local heuristic estimate otherwise.
- Keep management manual for now: history still grows until the user issues
  `/reset` (which already exists).

## Non-goals (this iteration)

- Auto-trimming or auto-summarizing old messages.
- Cost tracking or per-request billing.
- Detecting the model's context window automatically.

## Decisions

- **Measurement:** both provider usage and a heuristic fallback.
  - Provider: OpenAI-compatible streaming responses can carry
    `usage: {prompt_tokens, completion_tokens}` when the request includes
    `stream_options: {"include_usage": true}`.
  - Fallback: `TokenEstimator.estimateTokens(String) = ceil(chars / 4)`.
- **Management:** manual only (`/reset`). Warnings are advisory.
- **Limit:** configurable `maxContextTokens` (Integer, nullable). Unset means
  no warnings and no limit line in `/usage`. Values ≤ 0 are treated as unset.
- **Provider opt-out:** `includeUsage` (boolean, default `true`) controls
  whether `stream_options.include_usage` is sent. If a provider rejects the
  field with a 400, set `"includeUsage": false` in the config.

## Architecture

New types under `com.mrsmith`:

| Type | Package | Responsibility |
|---|---|---|
| `Usage` (record) | `provider` | `promptTokens`, `completionTokens` (both `Integer`, nullable); `total()` sums available fields |
| `ProviderResponse` (record) | `provider` | `message` (`ChatMessage`) + `usage` (`Usage`) + `usageEstimated` (boolean) |
| `SseResult` (record) | `provider` | `content` (String) + `usage` (nullable `Usage`) |
| `TokenEstimator` | `provider` | static `estimateTokens(String)` heuristic (chars/4) |
| `UsageTracker` | `chat` | Accumulates per-turn usage + session totals; produces the per-turn line and `/usage` text |

Changed types:

- `Provider.send(List<ChatMessage>, Consumer<String>)` returns `ProviderResponse`
  (was `ChatMessage`). All fakes updated.
- `SseParser.consume(...)` returns `SseResult` (was `String`); it parses `usage`
  out of chunks that carry it.
- `OpenAiCompatibleProvider` sends `stream_options.include_usage` when
  `config.includeUsage()` is true. It resolves the turn's usage itself: real
  usage from the stream if present, otherwise a heuristic estimate computed
  from the exact messages it serialized (system prompt + history) and the
  reply content. `usageEstimated` flags which case applied. `usage` is never
  null in the response.
- `AppConfig` gains `Integer maxContextTokens` and `boolean includeUsage`
  (default `true`).
- `ConfigLoader` reads `maxContextTokens` and `includeUsage` from the config
  file, env vars (`MRSMITH_MAX_CONTEXT`, `MRSMITH_INCLUDE_USAGE`), and CLI
  flags, with the usual precedence (CLI > env > file > defaults). CLI values
  are passed as a `CliConfig` record (replaces the growing positional-args
  overload).
- `ChatCommand` gains `--max-context` (Integer) and `--include-usage` (boolean).
- `ChatSession(Provider, IO, AppConfig)` records each turn in `UsageTracker`,
  prints the per-turn line, handles `/usage`, warns near the limit (reading
  `maxContextTokens` from config), and resets the tracker on `/reset`.

## Data Flow (one turn)

```
user input → history.add(USER)
ProviderResponse response = provider.send(history, sink)
    → request body includes stream_options.include_usage (if enabled)
    → SSE parsed into SseResult(content, usage)
    → if usage == null:
          prompt estimate  = Σ TokenEstimator.estimateTokens(msg.content)
                             over system prompt + history
          completion estimate = TokenEstimator.estimateTokens(reply content)
          usage = Usage(prompt estimate, completion estimate); usageEstimated = true
    → ProviderResponse(message = ChatMessage(ASSISTANT, content), usage, usageEstimated)
tracker.recordTurn(response.usage(), response.usageEstimated())
history.add(reply)
io.writeLine(tracker.lastTurnLine())
if maxContextTokens set and sessionTotal ≥ 85% → warn (once at 85%, once at 100%)
```

## Display

**Per-turn line** (printed after each assistant reply):

```
tokens: 1,234 in · 345 out · total 1,579 · session 12,345
```

Estimated values get an `(est.)` suffix:

```
tokens: 1,234 in (est.) · 345 out (est.) · total 1,579 · session 12,345 (est.)
```

**`/usage`:**

```
Session usage:
  prompt:        12,000
  completion:     3,456
  total:         15,456
  context limit: 128,000 configured (12% used)   ← only if maxContextTokens set
  history:           42 messages
```

**Near-limit warnings** (advisory, once per threshold):

```
Warning: session at 89% of your configured 128,000-token context limit — consider /reset
```

## Error Handling

| Scenario | Behavior |
|---|---|
| Provider returns no usage | Fallback to heuristic estimate, flagged `(est.)` |
| Provider returns partial usage | Use present fields; totals sum available |
| Malformed `usage` in SSE | SseParser warns + skips; usage stays null → estimate fallback |
| `includeUsage` rejected (400) | Surfaces as a normal provider error; set `includeUsage: false` in config |
| `maxContextTokens` unset or ≤ 0 | No warnings; `/usage` omits limit line |
| `/reset` | Clears history and resets the usage tracker |

Policy: usage problems never break the chat — they degrade silently to the
estimate.

## Testing

- `TokenEstimatorTest` — known inputs; empty string → 0.
- `SseParserTest` — usage extracted from the final chunk; absent usage → null;
  malformed usage → null, stream still works.
- `OpenAiCompatibleProviderTest` — request body contains
  `stream_options.include_usage` when enabled and omits it when disabled;
  response with usage → `ProviderResponse.usage` populated; without → null.
- `UsageTrackerTest` — accumulation, per-turn line format, session totals,
  reset, `(est.)` flag.
- `ChatSessionTest` — per-turn line printed; `/usage` output; 85%/100%
  warnings fire once each; estimate fallback when usage null; provider usage
  used when present; `/reset` resets tracker.
- `ConfigLoaderTest` — `maxContextTokens`/`includeUsage` parsing, precedence,
  defaults.

## Future Extensions (not now)

- Auto-trim oldest messages when the estimated history exceeds the budget.
- Auto-summarize/compact older turns with the LLM.
- Cost tracking from provider usage.
