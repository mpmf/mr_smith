# Design: Config Layering (`AgentRuntime`) + Env API Keys

Date: 2026-08-10

## Context

`AppConfig` is a flat record that mixes three concerns — provider-level
(`apiKey`, `baseUrl`), agent-level (`model`, `systemPrompt`, `maxContextTokens`,
`tools`, `maxToolRounds`, `maxToolCallsPerSession`), and global (`includeUsage`,
`sessionsDir`) — because `AgentCatalog.resolve` flattens a resolved agent into a
single object. `AppConfig.sessionsDir()` is dead (transcripts read
`catalog.sessionsDir()`). Separately, API keys currently live only in the config
file; there is no way to keep keys out of the file or rotate them via the
environment.

## Goal

- Resolve a selected agent into an explicit layered runtime
  `AgentRuntime(agent, provider, globals)` instead of a flat config record.
- Let providers source/override their API key from `MRSMITH_<PROVIDER>_API_KEY`
  environment variables (env overrides the file value).

## Scope

- New `config/AgentRuntime` record; `AgentCatalog.resolve` returns it.
- `ProviderFactory`, `ToolRegistryFactory`, `ChatSession`, `SubAgentRunner`,
  `OpenAiCompatibleProvider`, and `ChatCommand` consume `AgentRuntime`.
- `AppConfig` and `AppConfigTest` are deleted.
- `ConfigLoader` resolves provider API keys from env vars.
- README documents the env var.

## Non-Goals

- No per-agent sessions dir, no auto-discovery of providers via env (providers
  must still be declared in the config file).
- No env override for `baseUrl` or other provider fields.
- No change to `CliConfig`, config file schema, or precedence of
  CLI > env > file > defaults for existing settings.

## Architecture

### `AgentRuntime`

New `com.mrsmith.config.AgentRuntime`:

```java
public record AgentRuntime(AgentConfig agent, ProviderConfig provider, AgentRuntime.Globals globals) {

    public record Globals(boolean includeUsage) {
    }
}
```

`AgentCatalog.resolve(String)` returns
`new AgentRuntime(agent, provider, new AgentRuntime.Globals(includeUsage))`
instead of constructing a flat `AppConfig`. Agent/provider validation stays in
the `AgentCatalog` constructor, unchanged.

`ProviderConfig` gains a compact constructor that strips a trailing slash from
`baseUrl`, preserving the normalization the deleted `AppConfig` constructor
used to perform as a type-level invariant.

### Consumer updates

| Consumer | Change |
|---|---|
| `ProviderFactory.create` | `AppConfig config` → `AgentRuntime runtime` |
| `OpenAiCompatibleProvider` | field `AgentRuntime runtime`; `config.baseUrl()` → `runtime.provider().baseUrl()`; `config.apiKey()` → `runtime.provider().apiKey()`; `config.model()` → `runtime.agent().model()`; `config.includeUsage()` → `runtime.globals().includeUsage()` |
| `ToolRegistryFactory.create` | `AppConfig config` → `AgentRuntime runtime`; impl uses `runtime.agent().tools()` |
| `ChatSession` | field `AppConfig config` → `AgentRuntime runtime`; `runtime.agent().systemPrompt()`, `.maxToolRounds()`, `.maxContextTokens()`, `.maxToolCallsPerSession()` |
| `SubAgentRunner` | `Supplier<AppConfig>` → `Supplier<AgentRuntime>`; `maxToolRounds(runtime)` |
| `ChatCommand` | registry-factory lambda uses `config.agent().tools()`; `OpenAiCompatibleProvider::new` still satisfies `ProviderFactory` |

`AppConfig.java` is deleted.

### Env API keys

`ConfigLoader.parseProviders` gains the `env` map. For each provider declared in
the file, after reading `apiKey` from the file:

```java
String envVar = "MRSMITH_" + normalizeEnvName(name) + "_API_KEY";
String envKey = env.get(envVar);
if (envKey != null && !envKey.isBlank()) {
    apiKey = envKey;
}
```

where `normalizeEnvName(name)` = `name.toUpperCase(Locale.ROOT)` with every
non-`[A-Z0-9]` character replaced by `_` (e.g. `my-provider` → `MY_PROVIDER`,
so the var is `MRSMITH_MY_PROVIDER_API_KEY`). Env wins over the file value. A
provider with no file key and no env key still fails validation in
`AgentCatalog` as today.

## Error Handling

| Scenario | Behavior |
|---|---|
| Provider has file key + env var set | env key wins |
| Provider has no file key, env var set | env key used |
| Provider has no key anywhere | `AgentCatalog` rejects the provider as today |
| Provider name contains characters invalid in env names | normalized (`-` → `_`); README documents the mapping |

## Testing

- New `config/AgentRuntimeTest` — record accessors and `Globals`.
- `AgentCatalogTest` — `resolve` assertions move to `runtime.agent()` /
  `runtime.provider()` / `runtime.globals()` accessors.
- `ConfigLoaderTest` — existing resolve assertions updated; new cases:
  env key overrides file key; env key fills a missing key; env name
  normalization (`my-provider` → `MRSMITH_MY_PROVIDER_API_KEY`).
- `OpenAiCompatibleProviderTest` — constructs `AgentRuntime` instead of
  `AppConfig`.
- `ChatSessionTest` / `SubAgentRunnerTest` — `FakeProviderFactory.create`
  signature updated; existing assertions unchanged.
- `AppConfigTest` deleted.
- Full suite stays green (310 → ~same after the swap, minus `AppConfigTest`,
  plus `AgentRuntimeTest` and the new env-key cases).
