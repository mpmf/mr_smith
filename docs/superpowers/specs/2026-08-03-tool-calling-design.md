# Design: Tool Calling

Date: 2026-08-03

## Problem

Mr Smith is a single-turn chat harness: the user sends a message, the model
replies, and nothing else can happen. The model cannot run commands, read or
edit files, inspect the filesystem, or fetch web content — it can only produce
text. The original harness design explicitly deferred "agent/tool calling" to a
future extension; this spec implements it.

## Goal

Let the model request actions (tool calls) that Mr Smith executes on its behalf,
feeding the results back so the model can iterate until it produces a final
answer. The user approves destructive actions; read-only actions run
automatically.

## Scope

- OpenAI-compatible `tools`/`tool_calls`/`role:tool` wire format (the provider
  already speaks this dialect).
- An inner tool loop in `ChatSession` capped at 32 rounds per user turn
  (configurable per agent via `maxToolRounds`, default 32).
- An optional per-session tool budget (`maxToolCallsPerSession`) shared between
  the main loop and sub-agents, with an 80% warning and graceful exhaustion.
- Six built-in tools: `shell`, `write_file`, `read_file`, `list_dir`, `glob`,
  `web_fetch`.
- Per-agent opt-in: agents declare which tools they may use in `config.json`.
- Tool calls and results recorded in the session transcript.
- All file/command activity rooted at the CWD where mr-smith was launched.

## Non-Goals

- No user-defined or config-defined tools (no tool authoring without
  recompiling). Tools are Java classes.
- No parallel tool execution (tools run sequentially, in call order).
- No tool streaming, progress, or cancellation beyond the per-tool timeouts.
- No session persistence of tool state across sessions.
- No MCP or other external tool servers.

## Architecture

### Tool model

New package `com.mrsmith.tool`:

```java
public interface Tool {
    String name();
    String description();
    JsonNode parametersSchema();       // JSON Schema for the arguments object
    boolean isReadOnly();              // true = run without confirmation
    ToolResult execute(JsonNode args) throws ToolException;
}

public record ToolResult(String content, boolean error) {}
```

`ToolException` is a `RuntimeException` carrying a message safe to show in the
REPL and to send back to the model as the tool result.

`ToolCall` (in `com.mrsmith.provider`) models what the model requested:

```java
public record ToolCall(String id, String name, JsonNode arguments) {}
```

### Built-in tools

All tools operate relative to the process CWD (the directory where mr-smith was
launched). File tools resolve the requested path against the CWD, normalize it,
and refuse to touch paths that escape the CWD root (`ToolException`). Shell
commands run via `bash -c` with the CWD as the working directory.

| Tool         | Read-only | Arguments                                  | Behavior                                                                 |
|--------------|-----------|--------------------------------------------|--------------------------------------------------------------------------|
| `shell`      | no        | `{ "command": string }`                    | Runs `bash -c <command>`. Returns stdout, stderr, and exit code. 30s timeout. |
| `write_file` | no        | `{ "path": string, "content": string }`    | Writes content to `<cwd>/<path>`, creating parent directories. Confirmation required. |
| `read_file`  | yes       | `{ "path": string }`                       | Returns the file contents (capped at 1 MiB).                            |
| `list_dir`   | yes       | `{ "path": string }`                       | Lists directory entries (names, one per line).                          |
| `glob`       | yes       | `{ "pattern": string }`                    | Matches `<cwd>/<pattern>` (e.g. `src/**/*.java`), returns relative paths. |
| `web_fetch`  | yes       | `{ "url": string }`                        | GET the URL, follow redirects, 10s timeout, 1 MiB cap. Returns text.     |

Path resolution rules (all file tools):

- The argument `path`/`pattern` is resolved against the CWD root.
- The normalized result must start with the normalized CWD root; otherwise
  `ToolException("path escapes the working directory")`.
- `read_file`/`write_file`/`list_dir` also reject paths that point outside
  containment via symlinks (canonical path check).
- Absolute paths are allowed only if they resolve inside the CWD root.

The 30s shell timeout and 10s web_fetch timeout are safety defaults; a timed-out
tool returns an error result (does not crash the session).

### ToolRegistry

`ToolRegistry` maps names to `Tool` instances and exposes the enabled subset:

```java
public final class ToolRegistry {
    public static ToolRegistry with(List<String> toolNames);
    public Optional<Tool> find(String name);
    public List<Tool> tools();          // the enabled tools, for the request
    public boolean isEmpty();
}
```

Unknown names passed to `ToolRegistry.with` throw `ToolException` (callers
validate at config load, so this is a defensive backstop).

### Wire format

**Request.** `OpenAiCompatibleProvider.buildRequestBody` adds a `tools` array
when the session passes tools in. Each entry:

```json
{ "type": "function", "function": { "name": "...", "description": "...",
  "parameters": { ... } } }
```

`tool_choice` is omitted, so the provider defaults to `auto`.

**Messages.** `ChatMessage` gains two fields: `List<ToolCall> toolCalls`
(assistant only, null otherwise) and `String toolCallId` (tool role only, null
otherwise). `Role` gains `TOOL("tool")`.

Serialization in `buildRequestBody`:

- assistant with tool calls:
  `{ "role": "assistant", "content": null, "tool_calls": [ { "id": "...",
    "type": "function", "function": { "name": "...", "arguments": "<json>" } } ] }`
- tool result:
  `{ "role": "tool", "tool_call_id": "...", "content": "<result>" }`
- unchanged: system, user, plain assistant.

**Response.** `SseParser` accumulates tool-call deltas. Tool-call chunks arrive
as `choices[0].delta.tool_calls[]`, each with an `index`, and fields arrive
across multiple chunks: `id` and `function.name` are set on the first chunk that
carries them; `function.arguments` is a string that concatenates across chunks.
`SseParser.consume` accumulates per-index and returns completed tool calls in
`SseResult` (new field `List<ToolCall> toolCalls`). The provider builds the
assistant `ChatMessage` with them; `ProviderResponse` is unchanged
(`message.toolCalls()` carries them).

### Provider interface

`Provider.send` gains a tools parameter so the session controls what each send
advertises:

```java
ProviderResponse send(List<ChatMessage> context, List<Tool> tools,
                      Consumer<String> tokenSink, Consumer<String> reasoningSink);
```

### Session loop

In `ChatSession`, after appending the user message, the turn becomes an inner
loop (replacing the single `provider.send` at the seam identified in the current
code):

```
context = contextBuilder.messages()
rounds = 0
loop:
  response = provider.send(context, registry.tools(), io::write, io::writeReasoning)
  message = response.message()
  if message.toolCalls() empty:
      final = message; break
  if rounds >= maxToolRounds:                 // default 32
      if not userWantsToContinue():           // prompt "[y/N]" via IO
          for each call in message.toolCalls():
              context += ChatMessage(TOOL, "Tool round limit (<n>) reached; "
                  + "answer without more tool calls.", toolCallId = call.id())
          response = provider.send(context, registry.tools(), io::write, io::writeReasoning)
          final = response.message()          // reply text used; any tool calls dropped
          break
      rounds = -1                              // reset so the next iteration starts fresh
  history += message                         // assistant with tool_calls
  contextBuilder.appendAssistantToolCalls(message.toolCalls())
  transcript += one tool_call record per call
  for each call:
      if budget.exhausted():               // session budget (see below)
          for each remaining call: context += ChatMessage(TOOL, budgetMessage, toolCallId = call.id())
          response = provider.send(...); final = response.message(); break
      tool = registry.find(call.name())
      if tool absent:  result = ToolResult("Unknown tool: <name>", true)
      else if !tool.isReadOnly() and not confirmed: result = ToolResult("User declined to run <name>.", true)
      else: result = run(tool, call)        // ToolException -> error result
      budget.record()                       // count executed calls; warn once at 80%
      io.writeLine("tool: <name>(<summary>) -> ok|error")   // compact status
      history += ChatMessage(TOOL, result.content(), null, null, call.id())
      contextBuilder.appendToolResult(call.id(), result.content())
      transcript += one tool_result record
  rounds += 1
  // loop back; the model may call again or answer
```

The round-limit branch is not a hard stop: the loop prompts the user
(`Tool round limit (<n>) reached. Continue with <n> more tool rounds? [y/N] `)
via the same `IO` used for approval prompts. On **yes**, the round counter is
reset (`round = -1`) and the loop continues in the same turn with a fresh
`maxToolRounds`; on **no** (or EOF), it appends a `TOOL` result for each
pending call (using its real `tool_call_id`, which strict providers require to
reference a prior assistant tool_call), sends one final message asking the
model to answer without further tool calls, and uses that reply as final
(dropping any tool calls in it). The user decides each time whether to extend,
so the loop is not a hard stop unless the user chooses it to be. There is no
cap on extensions; the context-limit warnings still guard context growth.

### Session tool budget

`maxToolCallsPerSession` (optional per-agent, unlimited by default) bounds the
total number of **executed** tool calls across a session — the main loop *and*
sub-agents draw from the same `ToolBudget` instance, created fresh on `/reset`
and agent switches.

- `ToolBudget.record()` increments after each executed call and prints a one-time
  warning when usage crosses 80% of the limit (`Warning: tool call budget N% used
  (x/y) — consider /reset`).
- When `ToolBudget.exhausted()` is true before a call, the loop appends a `TOOL`
  result for each pending call (`"Session tool call budget exhausted (x/y). Give
  a brief status update and tell the user to /reset (or send 'continue') if more
  work is needed."`), sends one final message, and ends the turn gracefully.
- `/usage` reports the current count against the limit (`tool calls: x/y`).

Final answer handling (history, `contextBuilder.appendAssistant`,
`appendAssistant` transcript, usage line, warnings) is unchanged.

**Approval.** `shell` and `write_file` are not read-only. Before running one, the
session prompts `Run <name>(<args>) [y/N]?` via the `IO` interface and reads a
line; anything but `y`/`yes` (case-insensitive) is a decline, recorded as an
error tool result. Read-only tools (`read_file`, `list_dir`, `glob`,
`web_fetch`) run without prompting. Prompt input is read with the same
`io.readLine()` used for the main REPL; Ctrl-D at the prompt counts as decline.

**Status line.** Each executed tool prints one line to stdout via `io.writeLine`:
`tool: shell(ls -la) -> ok` or `tool: write_file(foo.txt) -> error`. The full
result is sent only to the model and the transcript, not echoed.

**Usage.** Prompt+completion tokens accumulate across every send in the turn
(including tool rounds) so `/usage` and warnings reflect the real cost. The
per-turn usage line and `warnIfNearLimit` behave as today but with the
accumulated total.

### Config

`AgentConfig` gains `List<String> tools` (default empty), `Integer maxToolRounds`
(default 32 when unset), and `Integer maxToolCallsPerSession` (null = unlimited).
`ConfigLoader` parses the optional per-agent `tools` array, `maxToolRounds`, and
`maxToolCallsPerSession`. `AgentCatalog` validates each tool name against the
built-in registry's known names at load and rejects non-positive round/budget
limits; unknown names throw `ConfigException` with the agent name. `AppConfig`
carries the tool names and limits; `AgentCatalog.resolve` merges them;
`ChatSession.applyAgent()` builds the `ToolRegistry` and `ToolBudget`.

Example agent:

```json
{
  "agents": [{
    "name": "coder",
    "provider": "opencode",
    "model": "deepseek-v4-flash",
    "tools": ["shell", "read_file", "write_file", "list_dir", "glob", "web_fetch"]
  }]
}
```

An agent with no `tools` array (or an empty one) has no tools and behaves
exactly as today.

### Context building

`ContextBuilder` gains:

```java
void appendAssistantToolCalls(List<ToolCall> toolCalls);
void appendToolResult(String toolCallId, String content);
```

`FullContextBuilder` stores these as `ChatMessage`s: an assistant message with
`content = null` and the tool calls, and a `Role.TOOL` message. Tool messages
flow to the wire like any other message (no thinking stored, matching today's
behavior).

### Transcripts

`TranscriptWriter` gains:

```java
void appendToolCall(UUID sessionId, String id, String name, JsonNode arguments) throws IOException;
void appendToolResult(UUID sessionId, String id, String content, boolean error) throws IOException;
```

`FileTranscriptWriter` writes:

```json
{ "type": "tool_call", "id": "call_x", "name": "shell", "arguments": { "command": "ls" }, "timestamp": "..." }
{ "type": "tool_result", "id": "call_x", "content": "...", "error": false, "timestamp": "..." }
```

`arguments` uses the already-serialized JSON (`JsonNode.toString()`).

## Error handling

- `ToolException` (from validation or execution) is caught per tool call and
  turned into an error `ToolResult`; the loop continues. The exception message
  is what the model and the user see on the status line.
- Timeouts (shell 30s, web_fetch 10s) produce error results, not crashes.
- Provider errors during any send keep the existing behavior (partial content
  paths, `ProviderException` handling) unchanged.
- Round-limit: after `maxToolRounds` tool rounds (default 32), the session
  prompts the user (`[y/N]`) to continue with `maxToolRounds` more rounds. On
  yes the counter resets and the loop continues in the same turn; on no it
  appends a `TOOL` message instructing the model to answer without more tool
  calls, sends once more, and uses that reply as final — dropping any tool
  calls it still contains. The user controls whether it is a hard stop.
- Session budget exhaustion: when `maxToolCallsPerSession` is configured and the
  budget runs out mid-turn, pending calls get a `TOOL` result explaining the
  budget is exhausted, one final send produces a status reply, and the turn ends
  gracefully. `/reset` starts a fresh budget.

## Testing

- `SseParserTest`: tool-call deltas split across chunks (arguments concatenation),
  id/name only on first chunk, multiple simultaneous calls by index, missing
  fields tolerated, mixed with content/reasoning.
- `OpenAiCompatibleProviderTest` (MockWebServer): `tools` array present in
  request body; `role:tool` and assistant-with-tool_calls serialization; tool-call
  SSE response surfaces in `message.toolCalls()`; `tools` empty => no `tools`
  field in body.
- `ToolRegistryTest`: enable subset, find/absent, empty registry.
- Per-tool unit tests: shell (stdout/stderr/exit code, timeout), file tools
  (read/write/list/glob, containment guard, symlink escape), web_fetch (happy
  path, redirect, timeout/size cap).
- `ChatSessionTest` (extend `FakeProvider`): tool loop executes and returns
  result to model; approval prompt (yes runs, no declines); unknown tool name;
  round-limit stops the loop; session budget stops the loop and warns at 80%;
  `/reset` grants a fresh budget; `/usage` reports the budget; transcript has
  tool_call/tool_result records; usage accumulates across rounds; no-tools agent
  unchanged.
- `SubAgentRunnerTest`: sub-agent tool calls count against the shared session
  budget.
- `ConfigLoaderTest`/`AgentCatalogTest`: `tools` parsing, `maxToolRounds` /
  `maxToolCallsPerSession` parsing and defaults, unknown-tool `ConfigException`,
  non-positive limit `ConfigException`, empty-tools default.
