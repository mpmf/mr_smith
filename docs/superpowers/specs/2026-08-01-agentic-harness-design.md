# Mr Smith — Agentic Harness Design

Date: 2026-08-01
Status: Approved

## Context

Mr Smith is a "basic agentic harness": a program that connects to LLM providers
via the OpenAI API and lets the user type text so the LLM answers. This is the
first iteration — a minimal interactive chat CLI — but the design deliberately
leaves seams for future extensions: web UI, Telegram, REST API, and agent/tool
calling.

## Goals

- Interactive multi-turn chat against any OpenAI-compatible endpoint, with
  token-by-token streaming output.
- Clean ports-and-adapters seams so future interfaces (web, Telegram, REST) and
  future providers/agents slot in as new implementations without touching core
  logic.
- No framework. Fast iteration, minimal dependencies.

## Non-goals (this iteration)

- Agent/tool calling loop, function calling, or any tool execution.
- Persistent conversation storage.
- Authentication beyond a single API key.
- Any web/telegram/REST interface.

## Decisions

- **Language:** Java 21 (LTS) — records, virtual threads, pattern matching.
- **Build:** Maven, single module.
- **CLI:** picocli for argument parsing, plain stdin/stdout loop.
- **HTTP:** JDK built-in `java.net.http.HttpClient`.
- **JSON:** Jackson.
- **Config:** config file + environment variables + CLI flags.
- **Provider scope:** any OpenAI-compatible endpoint (base URL + key + model).

## Architecture

Single Maven module with disciplined package boundaries under `com.mrsmith`:

| Package | Responsibility |
|---|---|
| `cli` | `Main` entry point + picocli `ChatCommand` |
| `io` | `IO` interface (port) + `ReplIo` (stdin/stdout) |
| `chat` | `ChatSession` — conversation loop, owns message history |
| `provider` | `Provider` interface (port) + `OpenAiCompatibleProvider` + message DTOs |
| `config` | `AppConfig` (record) + `ConfigLoader` (JSON config file + env + CLI) |

### Key types

- `Provider` — sends a chat conversation, streams response tokens through a
  `Sink<String>`, returns the assembled assistant message. Blocking.
- `IO` — `readLine()`, `write(String)`, `writeLine(String)`.
- `ChatSession` — owns `List<ChatMessage>` history; loops
  `readLine → provider.send → stream tokens to output → append assistant
  message to history`.
- `AppConfig` — `apiKey`, `baseUrl`, `model`, optional `systemPrompt`.
- `ChatMessage` — `role` (`system`/`user`/`assistant`) + `content`.

### Dependencies

- `info.picocli:picocli` — CLI
- `com.fasterxml.jackson.core:jackson-databind` — JSON
- Test: JUnit 5, OkHttp MockWebServer

## Data Flow

### Configuration load (startup)

1. Read config file `~/.config/mrsmith/config.json` (JSON, parsed with
   Jackson — no extra parser dependency) for defaults (base URL, model,
   optional system prompt).
2. Override with env vars: `OPENAI_API_KEY`, `MRSMITH_BASE_URL`,
   `MRSMITH_MODEL`.
3. Override with picocli flags (e.g. `--model`).
4. Build `AppConfig`; fail fast with a clear message if `apiKey` is missing.

### Chat loop (one turn)

```
ReplIo.readLine()                              → user prompt
append user ChatMessage to history
OpenAiCompatibleProvider.send(history, sink)
    → POST {baseUrl}/chat/completions
        (model, messages, stream=true)
    → parse SSE data lines
    → sink.accept(delta) → ReplIo.write(delta)  [live tokens]
    → assemble full assistant ChatMessage
append assistant message to history            → loop
```

### REPL commands

- `/exit` — quit
- `/reset` — clear history
- `/help` — usage
- anything else — sent to the LLM

### Streaming protocol

Request `stream=true`. Read SSE lines (`data:`), extract
`choices[0].delta.content`, stop on `[DONE]`. The provider assembles the full
text so history remains consistent even though output was streamed.

## Error Handling

| Scenario | Behavior |
|---|---|
| Missing/invalid `OPENAI_API_KEY` | Fail fast at startup, actionable message |
| HTTP 4xx (bad key, bad model) | Show provider error body; advise `/reset` or config fix; return to prompt |
| HTTP 5xx / network failure | Retry once with 2s backoff, then surface error, return to prompt |
| Malformed/truncated SSE | Use partial text if any; log warning; keep history consistent |
| Stream interrupted (Ctrl-C) | Keep partial text in history; return to prompt |
| Unknown `/command` | Show usage hint; do not call the LLM |
| Config file unreadable/malformed | Warn and fall back to env vars/defaults |

Policy: the harness never crashes on provider error — it recovers to the prompt
with a clear message. User-fault errors (bad key) are loud; transient errors are
retried quietly.

## Testing

- **Unit (no network):** `ConfigLoader` precedence and fallbacks; `ChatSession`
  history/commands using a fake `Provider`; SSE parser (deltas, `[DONE]`,
  malformed lines); provider request serialization (via MockWebServer).
- **Integration (MockWebServer):** end-to-end streaming against a fake
  OpenAI-compatible endpoint; 401/429/500 mappings.
- **Manual:** CLI smoke test against a real endpoint.
- No real API calls in CI.

## Future Extensions (not now)

- Tool/function calling and an agent loop inside `ChatSession`.
- New `IO` implementations: web UI, Telegram, REST handler.
- New `Provider` implementations for non-OpenAI-compatible services.
- Split into multiple Maven modules if boundaries demand it.
