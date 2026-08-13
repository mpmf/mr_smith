# Mr Smith — Current Context Size in Status Line Design

Date: 2026-08-13
Status: Approved

## Context

The per-turn status line and the `/usage` report show cumulative session usage
and the configured context limit, but not how much of the context is currently
occupied. With the sliding-window builder, the context sent to the provider is
bounded and differs from cumulative usage, so the user has no way to see how
full the current window is.

This design surfaces the context builder's estimated current context size (the
estimated token count of the messages that will be sent to the provider next)
in both the per-turn status line and the `/usage` report.

## Goals

- Add a way to ask a `ContextBuilder` for the estimated token size of its
  current context.
- Show that estimate in the per-turn status line and the `/usage` report.
- Keep the value clearly marked as an estimate (it is always a chars/4
  heuristic, never a real provider count).

## Non-goals (this iteration)

- Real token counts for the current context (no provider round-trip).
- Changing the near-limit warnings or the existing `context limit` display.
- Per-message size breakdown in the UI.

## Decisions

- **New interface method** `int estimatedTokens()` on `ContextBuilder`, with a
  **default** implementation that sums `TokenEstimator.estimateMessageTokens`
  over `messages()`. `FullContextBuilder` inherits the default unchanged.
- **`SlidingWindowContextBuilder` overrides** `estimatedTokens()` to return its
  cached `systemTokens + turnTokens` (O(1), no recompute).
- **Always labeled `(est.)`** since the value is always a heuristic.
- **Shown for both builders** — for `FullContextBuilder` it equals the
  full-history size; for the sliding builder it is the bounded window size.

## Architecture

Changed types:

| Type | Change |
|---|---|
| `ContextBuilder` | add `default int estimatedTokens()` summing `estimateMessageTokens` over `messages()` |
| `SlidingWindowContextBuilder` | override `estimatedTokens()` → `systemTokens + turnTokens` |
| `ChatSession` | append `· context %,d (est.)` to the per-turn line; add `context: %,d tokens (est.)` to `/usage` |

`FullContextBuilder` is unchanged (inherits the default method).

## Display

**Per-turn line** (after each assistant reply):

```
tokens: 1,234 in · 345 out · total 1,579 · session 12,345 · context 3,210 (est.)
```

**`/usage`:**

```
Session usage:
  prompt:      12,000
  completion:  3,456
  total:       15,456
  context limit: 128,000 configured (12% used)
  context:     3,210 tokens (est.)
  history:     42 messages
```

## Data Flow

```
ChatSession.run():
    ...
    tracker.recordTurn(turn.usage(), turn.estimated())
    String usageLine = tracker.lastTurnLine()
    if (!usageLine.isEmpty()):
        io.writeLine(usageLine + " · context %,d (est.)".format(contextBuilder.estimatedTokens()))

ChatSession.usageReport():
    ... existing lines ...
    report.append("  context: %,d tokens (est.)".format(contextBuilder.estimatedTokens()))
```

The per-turn line is printed after the assistant reply is appended to the
context, so the estimate includes the latest reply.

## Edge cases

| Scenario | Behavior |
|---|---|
| Empty context | `estimatedTokens()` returns 0 |
| `FullContextBuilder` | Sums all messages (default method) |
| Sliding builder mid-tool-loop | Cached total is current after each append |
| Trimmed sliding window | Cached total already excludes dropped turns |

## Testing

- `SlidingWindowContextBuilderTest` — `estimatedTokens` matches the tracked
  total, is 0 when empty, and drops after a trim.
- `FullContextBuilderTest` — `estimatedTokens` sums the messages (default
  method path).
- `ChatSessionTest` — per-turn line ends with `· context N (est.)`; `/usage`
  contains `context: N tokens (est.)`.
