# Mr Smith — History vs Context Separation Design

Date: 2026-08-01
Status: Approved

## Context

Today `ChatSession` treats its `history` list as both the full conversation
record and the context sent to the provider. These concepts can diverge: after a
future compaction, history stays complete while the context is a summarized
window. The reasoning text is already an exception — it is stored in history
(`thinking`) but excluded at request serialization.

This design separates the two concepts now (before compaction exists) so the
compaction feature later only changes how the context is derived.

## Goals

- Introduce a distinct **context** concept: the message list actually sent to
  the provider, derived from history each turn.
- Keep **history** as the full conversation record (displayed and written to the
  transcript, including thinking).
- Make the thinking exclusion explicit at the context boundary rather than an
  implicit serialization detail.
- Provide a clean seam (`ContextBuilder`) where compaction can plug in later.

## Non-goals (this iteration)

- Compaction or summarization (future feature).
- Windowing or trimming (future feature).
- Any change to the transcript format or the displayed conversation.

## Decisions

- **Context contents:** system prompt (if any) + all history messages with
  `thinking` stripped.
- **Where context is built:** a `ContextBuilder` port; for now the
  `FullContextBuilder` implementation returns the full derivation. A future
  compactor can be a different implementation or a composition.
- **Provider contract:** the provider serializes exactly the messages it is
  given and estimates usage over them; it no longer injects the system prompt.

## Architecture

New types in `com.mrsmith.chat`:

| Type | Kind | Responsibility |
|---|---|---|
| `ContextBuilder` | port (interface) | `List<ChatMessage> build(List<ChatMessage> history, String systemPrompt)` |
| `FullContextBuilder` | adapter | System message (if `systemPrompt` non-null) + history messages rebuilt as `ChatMessage(role, content)` (thinking dropped) |

Changed types:

- `ChatSession` gains a `ContextBuilder` (5th constructor arg). Each turn it
  builds the context and passes it to `provider.send(context, io::write,
  io::writeReasoning)`. `history` still stores the full record (with thinking).
- `OpenAiCompatibleProvider` — `buildRequestBody(List<ChatMessage> context)`
  serializes the given messages verbatim (no system-prompt prepend);
  `estimateUsage(List<ChatMessage> context, replyContent, thinking)` estimates
  prompt over the context messages (the system message is one of them).
- `ChatCommand` injects `new FullContextBuilder()`.

## Data Flow (one turn)

```
history.add(USER, line)                    // history keeps everything
appendUser(line)                           // transcript unchanged
context = contextBuilder.build(history, config.systemPrompt())
                                           //   → [system?, ...history without thinking]
provider.send(context, io::write, io::writeReasoning)
history.add(response.message())            // history stores thinking
appendAssistant(...)                       // transcript records thinking
```

## Error Handling

| Scenario | Behavior |
|---|---|
| History message with null content (thinking-only interruption) | Context preserves it; serialization coerces `null` → `""` (existing) |
| `systemPrompt` null | No system message in the context |
| Empty history | Context = `[system?]` only |
| Thinking on any history message | Always stripped from the context |
| Provider errors / retries | Unchanged (no new error paths) |

## Testing

- `FullContextBuilderTest` — system + history in order; null system prompt → no
  system message; thinking stripped everywhere; null content preserved; empty
  history.
- `ChatSessionTest` — provider receives the context (system + history, no
  thinking). `storesThinkingInHistory` is reworked to assert the provider does
  NOT receive thinking (the transcript test already covers thinking being
  recorded). A new test asserts a configured system prompt appears as the first
  message the provider receives.
- `OpenAiCompatibleProviderTest` — serializes the given messages verbatim; the
  old `includesSystemPromptWhenConfigured` is reworked to pass the system
  message explicitly (the provider no longer injects it).

## Future Extensions (not now)

- A compactor `ContextBuilder` that summarizes or windows the history.
- Optionally, a `Context` value type if the derivation grows.
