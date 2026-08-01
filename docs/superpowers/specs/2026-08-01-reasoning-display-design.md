# Mr Smith — Reasoning Display Design

Date: 2026-08-01
Status: Approved

## Context

Reasoning models (DeepSeek R1, OpenAI o1/o3, Qwen3, etc.) emit a "thinking"
stream alongside the visible answer. Today `SseParser` only reads
`delta.content`, so the thinking is silently discarded. This design surfaces
the reasoning process: it is streamed live to the user in yellow (via a new IO
capability) and stored on the assistant message in a dedicated `thinking` field
that is never sent back to the model.

## Goals

- Extract the thinking text from the streaming response (`reasoning_content`
  with a `reasoning` fallback).
- Stream it live in yellow through a new `IO` capability, so future web/Telegram
  adapters render it their own way.
- Store the thinking on the assistant `ChatMessage` in a dedicated `thinking`
  field, but exclude it from the request body when history is sent back
  (reasoning APIs reject `reasoning_content` in requests, and it saves tokens).

## Non-goals (this iteration)

- Collapsible/labeled thinking blocks (live streaming was chosen).
- A `/show-thinking` command (thinking is already in history).
- Cost or token accounting changes (reasoning tokens are already counted in the
  provider's reported completion usage).

## Decisions

- **Extraction:** read `delta.reasoning_content` first; fall back to
  `delta.reasoning`; if neither is present, `thinking` is null (non-reasoning
  models behave exactly as today).
- **Display:** stream reasoning chunks live in yellow; the answer streams in the
  normal color. No labels.
- **ANSI:** emitted only when stdout is a terminal (`System.console() != null`
  at `ReplIo` construction); piped/redirected output stays plain.
- **History:** `ChatMessage` gains a nullable `thinking` field plus a 2-arg
  convenience constructor (`thinking = null`). The request body serializer
  writes only `role` + `content`, so thinking is stored locally but never
  re-sent.

## Architecture

Changed types:

| Type | Change |
|---|---|
| `ChatMessage` | Gains `String thinking` (nullable); 2-arg convenience constructor |
| `SseResult` | Becomes `(String content, String thinking, Usage usage)` |
| `SseParser` | `consume(reader, contentSink, reasoningSink)` → `SseResult`; extracts and streams reasoning |
| `Provider` | `send(history, tokenSink, reasoningSink)` → `ProviderResponse` (message carries thinking) |
| `IO` | New method `void writeReasoning(String text)` |
| `ReplIo` | Wraps reasoning chunks in `\u001B[33m`…`\u001B[0m` when color is enabled |
| `OpenAiCompatibleProvider` | Builds assistant message with `thinking`; estimate counts thinking text |
| `ProviderException` | Gains optional `partialThinking` to preserve interrupted reasoning |
| `ChatSession` | Passes `io::writeReasoning`; stores reply (with thinking) in history |

## Data Flow (one turn)

```
user input → history.add(USER)
ProviderResponse response = provider.send(history, io::write, io::writeReasoning)
    → SseParser.consume(reader, contentSink, reasoningSink)
        → reasoning deltas → io.writeReasoning   [yellow, live]
        → content deltas     → io.write          [normal]
        → SseResult(content, thinking, usage)
    → ChatMessage(ASSISTANT, content, thinking)
    → usage resolution (estimate includes thinking length)
history.add(response.message())    // thinking stored, never re-sent
per-turn usage line + warnings     // unchanged
```

## Display

- Each reasoning chunk renders as `\u001B[33m` + text + `\u001B[0m` (yellow),
  streamed live. The per-chunk reset prevents color leaking into the answer.
- The answer streams plain.
- Non-TTY: no ANSI; thinking still printed as plain text.

## Error Handling

| Scenario | Behavior |
|---|---|
| Reasoning present, no visible content | Thinking still streamed; assistant message stored with `thinking`, empty `content` |
| No reasoning fields present | `thinking` null; behavior identical to today |
| Malformed SSE chunk | Existing warn + skip |
| Non-TTY stdout | No ANSI; thinking shown as plain text |
| Stream interrupted mid-reasoning | Partial thinking preserved with partial content so history matches what was displayed |
| Reasoning in the final usage chunk | Handled — reasoning reads the delta, usage reads the `usage` node |

## Testing

- `SseParserTest` — extracts `reasoning_content`; falls back to `reasoning`;
  both absent → null; reasoning reaches the reasoning sink; content + reasoning
  in one chunk both handled.
- `ChatMessageTest` — 2-arg ctor → `thinking` null; 3-arg stores it.
- `ReplIoTest` — `writeReasoning` emits ANSI-yellow when `colorEnabled=true`,
  plain when false.
- `OpenAiCompatibleProviderTest` — request body contains no reasoning field;
  response message carries `thinking`; reasoning sink receives deltas; estimate
  counts thinking length.
- `ChatSessionTest` — `io::writeReasoning` receives thinking; stored `thinking`
  in history; interruption mid-reasoning preserves partial thinking.
