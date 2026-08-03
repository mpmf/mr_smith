# Mr Smith — Agents Design

Date: 2026-08-01
Status: Approved

## Context

The config file currently defines a single provider (base URL, API key, model,
system prompt, context limit). This design generalizes it: **providers** own the
credentials/endpoint, and **agents** combine a provider with a model, system
prompt, and context limit. Multiple named agents can be defined, one is the
default, and the user can select an agent at startup (`--agent`) or switch
mid-session (`/agent`, starting a new session).

## Goals

- Define multiple named providers (name + apiKey + baseUrl) and multiple named
  agents (name + provider reference + model + systemPrompt + maxContextTokens).
- Define the default agent used at startup.
- Select an agent at startup with `--agent <name>` and switch mid-session with
  `/agent <name>` (new session/UUID).
- Agents are configured entirely in the config file (no per-field CLI/env
  overrides); `--agent` and `--sessions-dir` are the only flags.

## Non-goals (this iteration)

- Per-agent `includeUsage` (it stays global).
- Listing agents in a rich way beyond `/agents`.
- Agent-specific session storage directories.

## Decisions

- **Config-only:** provider/agent fields come only from the config file. The
  `MRSMITH_MODEL`/`MRSMITH_BASE_URL`/`MRSMITH_MAX_CONTEXT`/`MRSMITH_INCLUDE_USAGE`
  env vars and `OPENAI_API_KEY` are no longer read. `MRSMITH_SESSIONS_DIR` and
  `--sessions-dir` still apply to the global sessions directory.
- **includeUsage** is global (top-level, default true).
- **Switching agents** starts a new session (new UUID, new folder, fresh
  context window and transcript).
- **Resolution:** an agent's runtime `AppConfig` merges `apiKey`+`baseUrl` from
  its provider, `model`/`systemPrompt`/`maxContextTokens` from the agent, and
  global `includeUsage` + `sessionsDir`.

## Config Format

```json
{
  "providers": [
    { "name": "opencode", "apiKey": "sk-...", "baseUrl": "https://opencode.ai/zen/go/v1" }
  ],
  "agents": [
    { "name": "coder", "provider": "opencode", "model": "opencode-go/deepseek-v4-flash",
      "systemPrompt": "...", "maxContextTokens": 128000 }
  ],
  "defaultAgent": "coder",
  "includeUsage": true,
  "sessionsDir": "..."
}
```

`systemPrompt` and `maxContextTokens` are optional per agent.

## Architecture

New types in `com.mrsmith.config`:

| Type | Kind | Responsibility |
|---|---|---|
| `ProviderConfig` | record | `name`, `apiKey`, `baseUrl` |
| `AgentConfig` | record | `name`, `provider`, `model`, `systemPrompt`, `maxContextTokens` |
| `AgentCatalog` | class | Validated parsed config; `resolve(String agentName)` → `AppConfig`; `defaultName()`; `agentNames()` |

New type in `com.mrsmith.provider`:

| Type | Kind | Responsibility |
|---|---|---|
| `ProviderFactory` | interface | `Provider create(AppConfig config)` |

Changed types:

- `ChatSession` ctor becomes `(IO, TranscriptWriter, ContextBuilder,
  AgentCatalog, ProviderFactory, String initialAgentName)`. It holds the current
  agent name + resolved `AppConfig` (mutable) + `Provider` (rebuilt on
  `/agent`). The banner prints `Agent: <name>` and `Session: <uuid>`.
- `ConfigLoader.load(CliConfig)` returns `AgentCatalog` (was `AppConfig`).
- `CliConfig` shrinks to `(String agent, Path sessionsDir)`.
- `ChatCommand` keeps only `--agent` and `--sessions-dir`; it computes the
  initial agent name (`--agent` or the default), wires
  `OpenAiCompatibleProvider::new` as the factory, and constructs the session.

## Startup Flow

```
ChatCommand.call():
  catalog = ConfigLoader.load(new CliConfig(agentFlag, sessionsDirFlag))
  agentName = agentFlag != null ? agentFlag : catalog.defaultName()
  io = new ReplIo()
  transcripts = new FileTranscriptWriter(catalog.sessionsDir())
  contextBuilder = new FullContextBuilder()
  session = new ChatSession(io, transcripts, contextBuilder, catalog,
                            OpenAiCompatibleProvider::new, agentName)
  session.run()
```

`ChatSession` resolves the initial agent and creates the provider itself.

## /agent Switching

```
/agent <name>:
  unknown name → "Unknown agent: <name>" (current agent unchanged)
  known name   → currentAgentName = name
                 currentConfig  = catalog.resolve(name)
                 provider       = providerFactory.create(currentConfig)
                 startNewSession()   [new UUID + folder + contextBuilder.start(systemPrompt)]
                 history/tracker cleared
                 prints "Agent: <name>" + "Session: <uuid>"
```

- `/help` mentions `/agent <name>`; `/agents` lists the available names.

## Error Handling

| Scenario | Behavior |
|---|---|
| Missing/invalid `defaultAgent` | `ConfigException` at load |
| `--agent` unknown name | `ConfigException` at startup |
| `/agent` unknown name | `Unknown agent: <name>`, stay on current agent |
| Agent references an unknown provider | `ConfigException` at load |
| Duplicate provider/agent names | `ConfigException` at load |
| No agents defined | `ConfigException` at load |
| Missing or malformed config file | `ConfigException` at load (no agents to run) |

## Testing

- `AgentCatalogTest` — validation (duplicate names, bad provider references,
  missing default, no agents); `resolve` merges provider + agent + globals;
  unknown name throws.
- `ConfigLoaderTest` — parses the new format; `sessionsDir`/`includeUsage`
  defaults; malformed file fallback.
- `ChatSessionTest` — `/agent` rebuilds the provider (fake factory counts
  calls), starts a new session, and re-seeds the context; unknown `/agent`
  handled; banner shows the agent name.
- `ChatCommandTest` — `--agent` present in help; removed flags absent.

## Future Extensions (not now)

- Per-agent `includeUsage` or session directories.
- Rich agent listing/inspection.
