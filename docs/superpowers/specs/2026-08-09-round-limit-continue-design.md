# Design: Round-Limit Continue Prompt

Date: 2026-08-09

## Problem

When a turn reaches its tool-round cap (`maxToolRounds`, default 32), the loop
currently hard-stops: it injects a "round limit reached" message and forces the
model to produce a final answer. The model may be mid-work, so the forced reply
often contains incomplete information. Work is truncated at an arbitrary
boundary.

## Goal

Turn the round limit into a user decision rather than an unconditional stop.
When the limit is reached, the loop asks the user whether to continue with a
fresh set of `maxToolRounds` rounds. Yes keeps the work going in the same turn;
no (or EOF) performs the current hard stop. This applies to the main loop and
sub-agent loops alike (they share `ToolLoop`).

## Scope

- A blocking `[y/N]` prompt in `ToolLoop.run` at the round-limit branch.
- On yes: reset the round counter and continue processing the pending calls.
- On no/EOF: the existing hard stop (inject the limit message, force a final
  answer).
- Revert the limit message from the "tell the user to send 'continue'" hint to
  the simple "answer without more tool calls." form.
- The per-session `ToolBudget` (`maxToolCallsPerSession`) is unchanged — it
  remains a separate, hard session bound with its own graceful-stop message.

## Non-Goals

- No cap on how many times the user may extend (the user decides each time; the
  85%/100% context-limit warnings still guard context growth).
- No changes to the session tool budget behavior or message.
- No transcript record of the extension decision (the extended rounds' tool
  calls/results are recorded normally via the existing sink).

## Architecture

### The prompt

In `ToolLoop.run`, the round-limit branch changes from an unconditional hard
stop to a user decision:

```
if rounds >= maxToolRounds:
    if not userWantsToContinue():          // "[y/N]" via IO
        inject "Tool round limit (N) reached; answer without more tool calls."
        return forced final answer
    rounds = -1                            // next iteration restarts at 0
process the current calls normally
```

`userWantsToContinue(io, maxToolRounds)` prints via the colored `IO.writePrompt`
(`Tool round limit (N) reached. Continue with N more tool rounds? [y/N] `) and
reads one line. `y`/`yes` (case-insensitive) continue; anything else, `null`
(EOF), or an `IOException` decline. It mirrors the existing approval
`confirm()` helper exactly.

- **Yes** → `round = -1`; the for-loop's `round++` restarts the count at 0, so
  the turn gets a fresh `maxToolRounds`. The pending calls (from the response
  that tripped the limit) are processed normally — executed, results fed back.
- **No / EOF** → the previous hard-stop path: a `TOOL` result
  `Tool round limit (N) reached; answer without more tool calls.` for each
  pending call (using its real `tool_call_id`), then one final send, whose
  reply is the turn's answer (any tool calls in it dropped). The loop always
  terminates on decline.

### Round-limit message

`roundLimitMessage` reverts to the simple form
`Tool round limit (N) reached; answer without more tool calls.` — the loop no
longer instructs the model to ask the user to type `continue`, because the loop
itself handles the continue decision.

### Budget interaction

The `ToolBudget` per-call check is untouched. Even after a round-limit
extension, the per-session budget still stops execution once
`maxToolCallsPerSession` is exhausted (with its own graceful-stop message
telling the user to `/reset` or send `continue`).

## Error handling

| Scenario | Behavior |
|---|---|
| Round limit reached, user answers `y`/`yes` | counter reset; loop continues in the same turn |
| Round limit reached, user answers anything else | hard stop: limit message injected, forced final answer |
| Round limit reached, EOF / `IOException` at the prompt | treated as decline; hard stop (safe for piped/headless runs) |
| Budget exhausted after an extension | budget's graceful stop applies (unchanged) |

## Testing

- `ChatSessionTest`:
  - `stopsAtToolRoundLimit` (decline, default 8 → 10 provider calls) and
    `toolRoundLimitHonorsConfig` (decline, limit 2 → 4 calls) now supply `n` at
    the prompt; assertions unchanged.
  - `continuesToolRoundsWhenUserExtends` — limit 2, `y` at the first limit then
    decline: 7 provider calls, 5 tool executions (extension ran a fresh set of
    rounds), and the prompt text is visible in the output.
- `SubAgentRunnerTest`:
  - `subAgentContinuesToolRoundsWhenUserExtends` — sub-agent agent with limit 2,
    `y` then `n`: 6 planned calls, 5 executed (the shared loop prompts for
    sub-agents too).
