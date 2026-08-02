# Mr Smith — History vs Context Separation Design

Date: 2026-08-01
Status: Approved (revised 2026-08-02: ContextBuilder is incremental and stateful)

## Context

Today `ChatSession` treats its `history` list as both the full conversation
record and the context sent to the provider. These concepts can diverge: after
future compaction, history stays complete while the context is a bounded,
possibly summarized window. The reasoning text is already an exception — it is
stored in history (`thinking`) but excluded from the context.

This design separates the two concepts now (before compaction exists) by making
the context a **stateful, incrementally-built window** that future strategies
(sliding window, compaction at a fraction of the context limit, ignoring small
interactions, replay/resume) can manage differently.

## Goals

- Introduce a distinct **context** concept: the message list actually sent to
  the provider, maintained incrementally as interactions happen.
- Keep **history** as the full conversation record (displayed and written to the
  transcript, including thinking).
- Feed the context builder one interaction at a time (user message, assistant
  reply) and let it keep the context current.
- Provide a strategy seam so future compaction/windowing changes only how the
  builder updates its context.

## Non-goals (this iteration)

- Compaction, summarization, windowing, or interaction filtering (future
  strategies — the interface is the seam).
- Any change to the transcript format or the displayed conversation.

## Decisions

- **Incremental interface:** the `ContextBuilder` is stateful. `start(prompt)`
  resets it for a session; `appendUser(content)` and `appendAssistant(content)`
  feed interactions; `messages()` returns the current context.
- **Default strategy:** full accumulation — every interaction is appended and
  `messages()` returns everything (system prompt + all turns, no thinking).
  Behavior is identical to today; the incremental seam is what's new.
- **Thinking:** never part of the context. `appendAssistant` takes content only;
  thinking stays in `history` and the transcript.
- **Provider contract:** the provider serializes exactly the messages it is
  given and estimates usage over them; it no longer injects the system prompt.

## Architecture

New types in `com.mrsmith.chat`:

| Type | Kind | Responsibility |
|---|---|---|
| `ContextBuilder` | port (interface) | `start(String systemPrompt)`, `appendUser(String content)`, `appendAssistant(String content)`, `List<ChatMessage> messages()` |
| `FullContextBuilder` | adapter | Full-accumulation strategy; `start` clears and seeds the system message; `messages()` returns an immutable snapshot |

Changed types:

- `ChatSession` holds a `ContextBuilder` (5th constructor arg). It calls
  `start(config.systemPrompt())` at session start and on `/reset`; per turn it
  calls `appendUser(line)`, reads `messages()`, passes the context to
  `provider.send`, then `appendAssistant(reply.content())` (and
  `appendAssistant(partialContent)` on an interrupted partial reply). `history`
  (with thinking) is still kept for the transcript.
- `OpenAiCompatibleProvider` — `buildRequestBody(List<ChatMessage> context)`
  serializes the given messages verbatim (no system-prompt prepend);
  `estimateUsage` estimates prompt over the context messages.
- `ChatCommand` injects `new FullContextBuilder()`.

## Data Flow (one turn)

```
run(): builder.start(config.systemPrompt())
loop:
  /reset → history.clear(); builder.start(systemPrompt); tracker.reset()
  user message:
    history.add(USER, line)                // history keeps everything
    builder.appendUser(line)
    context = builder.messages()           // immutable snapshot to send
    provider.send(context, io::write, io::writeReasoning)
    history.add(response.message())        // history stores thinking
    builder.appendAssistant(response.message().content())
  interrupted partial:
    history.add(partial); builder.appendAssistant(partialContent)
```

## Error Handling

| Scenario | Behavior |
|---|---|
| History message with null content (thinking-only interruption) | Context preserves it; serialization coerces `null` → `""` (existing) |
| `systemPrompt` null | `start` seeds no system message |
| `/reset` | `start()` clears the builder's window and re-seeds the system prompt |
| Transcript failures | Do not affect the builder (context stays correct) |
| Provider errors / retries | Unchanged (no new error paths) |

## Testing

- `FullContextBuilderTest` — `start` seeds the system message (or none when
  null); `appendUser`/`appendAssistant` accumulate in order; `messages()`
  returns all content with no thinking; `messages()` is immutable; `start()`
  resets the window.
- `ChatSessionTest` — provider receives `builder.messages()`; the existing
  `receivedHistories` content assertions still hold (no system prompt in test
  config); `thinkingIsNotSentToProvider`; `includesSystemMessageInContext`;
  partial-interruption appends partial content to the context.
- `OpenAiCompatibleProviderTest` — serializes the given messages verbatim
  (system message passed explicitly when present).

## Future Extensions (not now)

- New `ContextBuilder` implementations: sliding-window, compaction at a
  fraction of the configured context limit, ignoring small interactions,
  replaying/resuming a transcript. Extract a `ContextStrategy` if/when a second
  strategy appears.
