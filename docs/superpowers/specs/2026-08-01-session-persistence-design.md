# Mr Smith — Session Persistence Design

Date: 2026-08-01
Status: Approved

## Context

Every chat session is currently ephemeral: nothing is recorded on disk. This
design gives each session a unique UUID, stores each session's interactions
under a folder named with that UUID, and keeps a transcript file up to date with
the user requests, the reasoning (thinking), and the responses.

The transcript is written through a `TranscriptWriter` port, so a future backend
(e.g. a database) can replace the filesystem implementation without touching the
session logic.

## Goals

- Assign a unique UUID to each session at startup.
- Create `<sessionsRoot>/<uuid>/` and keep `transcript.jsonl` inside it updated
  with every interaction: user request, thinking, and assistant response.
- Allow the sessions directory to be configured (default
  `~/.config/mrsmith/sessions`).
- Abstract transcript storage behind a port so a database backend can replace
  the filesystem later.

## Non-goals (this iteration)

- Session resume/restore (loading a past session back into a chat).
- Listing or inspecting past sessions from the CLI.
- Cost accounting or analytics over transcripts.

## Decisions

- **Format:** JSONL — one JSON object per interaction, appended per turn.
- **Location:** configurable `sessionsDir` (CLI `--sessions-dir` > env
  `MRSMITH_SESSIONS_DIR` > config file `sessionsDir` > default
  `~/.config/mrsmith/sessions`).
- **Recorded data:** user request; assistant reply with thinking; per-turn token
  usage (real or estimated); ISO-8601 UTC timestamp on every record.
- **`/reset`:** starts a new session (new UUID, new folder, fresh transcript).
- **Portability:** `TranscriptWriter` port with a `FileTranscriptWriter` adapter
  (JSONL to the filesystem); a future `DbTranscriptWriter` implements the same
  port.

## Architecture

New package `com.mrsmith.session`:

| Type | Kind | Responsibility |
|---|---|---|
| `TranscriptWriter` | port (interface) | `start(UUID)`, `appendUser(UUID, content)`, `appendAssistant(UUID, content, thinking, usage, estimated)` — all keyed by session id |
| `FileTranscriptWriter` | adapter | Writes JSONL under `<sessionsRoot>/<uuid>/transcript.jsonl`; creates the folder on `start` |

Changed types:

- `ChatSession` gains `TranscriptWriter transcripts` and `UUID currentSessionId`
  (nullable). On startup and on `/reset` it generates a fresh UUID, calls
  `transcripts.start(id)`, and prints `Session: <uuid>` in the banner. It
  appends a user record after each user message and an assistant record after
  each reply (including partial-content recovery, without usage).
- `AppConfig` gains `Path sessionsDir` (nullable); the 4-arg and 6-arg
  convenience constructors are preserved.
- `CliConfig` gains `Path sessionsDir` (nullable).
- `ConfigLoader` reads `sessionsDir` (file key / env / CLI).
- `ChatCommand` gains `--sessions-dir`, resolves the default
  (`~/.config/mrsmith/sessions`), constructs `new FileTranscriptWriter(...)`,
  and injects it into `ChatSession` as the `TranscriptWriter`.

## Data Flow (one turn)

```
run(): sessionId = UUID.randomUUID()
       transcripts.start(sessionId)          → creates <root>/<uuid>/
       banner: "Session: <uuid>"
loop:
  /reset → new UUID + start() + banner + clear history/tracker
  user message:
       history.add(USER, line)
       transcripts.appendUser(sessionId, line)         [best-effort]
       response = provider.send(history, io::write, io::writeReasoning)
       history.add(response.message())
       transcripts.appendAssistant(sessionId,
           content, thinking, usage, usageEstimated)
  ProviderException with partial → appendAssistant(sessionId,
           partialContent, partialThinking, null, false)
```

## Record Shapes (JSONL)

```
{"type":"user","content":"...","timestamp":"2026-08-02T00:30:00Z"}
{"type":"assistant","content":"...","thinking":"...","promptTokens":1200,"completionTokens":300,"estimated":false,"timestamp":"..."}
```

- `thinking` is omitted when null (non-reasoning models).
- `promptTokens`/`completionTokens` are omitted when usage is null
  (e.g. partial-content recovery).
- Timestamps are `Instant.now()` in UTC.

## Writing Mechanics

- Each append opens the transcript with `CREATE|APPEND`, writes one line, and
  closes it — no lingering file handle.
- Path: `<sessionsRoot>/<uuid>/transcript.jsonl`.
- Serialization via Jackson.

## Error Handling

| Scenario | Behavior |
|---|---|
| `start()` fails (can't create folder) | Warn on stderr; disable transcript logging for this session; chat continues |
| `appendUser`/`appendAssistant` fails (disk/permissions) | Warn once; disable logging for the rest of the session; chat continues |
| `/reset` while logging disabled | New UUID + `start()` retried (resumes if the folder became writable) |
| Unknown `/commands` | Not recorded (not a model interaction) |
| Interrupted turn with partial content | Partial content + thinking recorded as an assistant record (no usage) |

Policy: session logging is best-effort — a disk problem never breaks the chat.

## Testing

- `FileTranscriptWriterTest` (with `@TempDir`) — `start` creates the folder;
  `appendUser`/`appendAssistant` write valid parseable JSONL; `thinking`/usage
  omitted when null; appends accumulate as lines.
- `ChatSessionTest` (fake `TranscriptWriter`) — session started with a printed
  UUID; `appendUser`/`appendAssistant` called with the correct data; `/reset`
  → new UUID + new start; provider error records partial content; writer
  `IOException` → chat continues.
- `ConfigLoaderTest` — `sessionsDir` from file/env/CLI + default resolution.
- `ChatCommandTest` — `--sessions-dir` option present in help.

## Future Extensions (not now)

- `DbTranscriptWriter` implementing `TranscriptWriter` against a database.
- Session resume and listing commands.
