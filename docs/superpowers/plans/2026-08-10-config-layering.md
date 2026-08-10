# Config Layering (`AgentRuntime`) + Env API Keys Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat `AppConfig` with a layered `AgentRuntime(agent, provider, globals)` resolved by `AgentCatalog`, and let providers source/override their API key from `MRSMITH_<PROVIDER>_API_KEY` env vars (env wins over the file).

**Architecture:** `AgentCatalog.resolve` returns `new AgentRuntime(agent, provider, new Globals(includeUsage))`. `ProviderFactory`, `ToolRegistryFactory`, `OpenAiCompatibleProvider`, `ChatSession`, `SubAgentRunner`, and `ChatCommand` consume `AgentRuntime`; `AppConfig` is deleted. `ConfigLoader.parseProviders` resolves each provider's key from the env map and keeps the existing baseUrl trailing-slash stripping. Spec: `docs/superpowers/specs/2026-08-10-config-layering-design.md`.

**Tech Stack:** Java 21, JUnit 5 (Jupiter), Maven.

---

## File Structure

**Create (main):**
- `src/main/java/com/mrsmith/config/AgentRuntime.java`

**Create (test):**
- `src/test/java/com/mrsmith/config/AgentRuntimeTest.java`

**Modify (main):**
- `src/main/java/com/mrsmith/config/AgentCatalog.java` — `resolve` returns `AgentRuntime`
- `src/main/java/com/mrsmith/config/ConfigLoader.java` — `parseProviders` gains the env map
- `src/main/java/com/mrsmith/config/ProviderConfig.java` — compact constructor strips a trailing slash from `baseUrl` (moved from the deleted `AppConfig` constructor)
- `src/main/java/com/mrsmith/provider/ProviderFactory.java` — `create(AgentRuntime)`
- `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java` — holds `AgentRuntime runtime`
- `src/main/java/com/mrsmith/tool/ToolRegistryFactory.java` — `create(AgentRuntime, ...)`
- `src/main/java/com/mrsmith/chat/ChatSession.java` — field `AgentRuntime runtime`
- `src/main/java/com/mrsmith/chat/SubAgentRunner.java` — `Supplier<AgentRuntime>`
- `src/main/java/com/mrsmith/cli/ChatCommand.java` — registry lambda uses `runtime.agent().tools()`

**Delete (main):**
- `src/main/java/com/mrsmith/config/AppConfig.java`

**Modify (test):**
- `src/test/java/com/mrsmith/config/AgentCatalogTest.java` — layered accessors
- `src/test/java/com/mrsmith/config/ConfigLoaderTest.java` — layered accessors + trailing-slash case
- `src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java` — builds `AgentRuntime`
- `src/test/java/com/mrsmith/chat/ChatSessionTest.java` — `FakeProviderFactory.create(AgentRuntime)`
- `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java` — `FakeProviderFactory.create(AgentRuntime)`

**Delete (test):**
- `src/test/java/com/mrsmith/config/AppConfigTest.java`

**Modify (docs):**
- `README.md` — `MRSMITH_<PROVIDER>_API_KEY` documentation

---

### Task 1: Replace flat `AppConfig` with layered `AgentRuntime`

This is an atomic refactor: `AgentCatalog.resolve` changes its return type and every consumer changes with it, so all main-source and test-source edits land together in one task/commit.

**Files:** all files in the File Structure section except `ConfigLoader`'s env-key behavior (that is Task 2; the `parseProviders` signature change for the env map happens here so Task 2 is additive).

- [ ] **Step 1: Write the failing test for the new record**

Create `src/test/java/com/mrsmith/config/AgentRuntimeTest.java`:

```java
package com.mrsmith.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentRuntimeTest {

    @Test
    void exposesAgentProviderAndGlobals() {
        AgentConfig agent = new AgentConfig("coder", "p", "model-x", "sys", 8192, 5, 200, List.of("shell"));
        ProviderConfig provider = new ProviderConfig("p", "sk", "https://example.com/v1");
        AgentRuntime runtime = new AgentRuntime(agent, provider, new AgentRuntime.Globals(false));
        assertEquals(agent, runtime.agent());
        assertEquals(provider, runtime.provider());
        assertFalse(runtime.globals().includeUsage());
    }
}
```

Run: `mvn -q -Dtest=AgentRuntimeTest test`
Expected: FAIL — compilation error, `AgentRuntime` not defined.

- [ ] **Step 2: Create `AgentRuntime`**

Create `src/main/java/com/mrsmith/config/AgentRuntime.java`:

```java
package com.mrsmith.config;

public record AgentRuntime(AgentConfig agent, ProviderConfig provider, AgentRuntime.Globals globals) {

    public record Globals(boolean includeUsage) {
    }
}
```

Run: `mvn -q -Dtest=AgentRuntimeTest test`
Expected: PASS.

- [ ] **Step 3: Update `AgentCatalog.resolve`**

In `src/main/java/com/mrsmith/config/AgentCatalog.java`, replace the `resolve` method:

```java
    public AppConfig resolve(String agentName) {
        AgentConfig agent = agents.get(agentName);
        if (agent == null) {
            throw new ConfigException("Unknown agent: " + agentName);
        }
        ProviderConfig provider = providers.get(agent.provider());
        return new AppConfig(provider.apiKey(), provider.baseUrl(), agent.model(),
                agent.systemPrompt(), agent.maxContextTokens(), includeUsage, sessionsDir, agent.tools(),
                agent.maxToolRounds(), agent.maxToolCallsPerSession());
    }
```

with:

```java
    public AgentRuntime resolve(String agentName) {
        AgentConfig agent = agents.get(agentName);
        if (agent == null) {
            throw new ConfigException("Unknown agent: " + agentName);
        }
        ProviderConfig provider = providers.get(agent.provider());
        return new AgentRuntime(agent, provider, new AgentRuntime.Globals(includeUsage));
    }
```

- [ ] **Step 4: Update `ConfigLoader.parseProviders` and `ProviderConfig`**

In `src/main/java/com/mrsmith/config/ConfigLoader.java`:

1. Add `import java.util.Locale;`.
2. Change the call `List<ProviderConfig> providers = parseProviders(root);` to `List<ProviderConfig> providers = parseProviders(root, env);`.
3. Replace the `parseProviders` method and add the `normalizeEnvName` helper:

```java
    private static List<ProviderConfig> parseProviders(JsonNode root, Map<String, String> env) {
        List<ProviderConfig> result = new ArrayList<>();
        JsonNode arr = root.path("providers");
        if (arr.isArray()) {
            for (JsonNode node : arr) {
                String name = node.path("name").asText();
                String apiKey = node.path("apiKey").asText(null);
                String envKey = env.get("MRSMITH_" + normalizeEnvName(name) + "_API_KEY");
                if (envKey != null && !envKey.isBlank()) {
                    apiKey = envKey;
                }
                result.add(new ProviderConfig(name, apiKey, node.path("baseUrl").asText()));
            }
        }
        return result;
    }

    private static String normalizeEnvName(String name) {
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }
```

In `src/main/java/com/mrsmith/config/ProviderConfig.java`, add a compact constructor that strips a trailing slash from `baseUrl` (this preserves the behavior `AppConfig`'s constructor previously had, as a type-level invariant):

```java
package com.mrsmith.config;

public record ProviderConfig(String name, String apiKey, String baseUrl) {

    public ProviderConfig {
        if (baseUrl != null && baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }
}
```

- [ ] **Step 5: Update `ProviderFactory` and `ToolRegistryFactory`**

Replace the full content of `src/main/java/com/mrsmith/provider/ProviderFactory.java`:

```java
package com.mrsmith.provider;

import com.mrsmith.config.AgentRuntime;

public interface ProviderFactory {

    Provider create(AgentRuntime runtime);
}
```

Replace the full content of `src/main/java/com/mrsmith/tool/ToolRegistryFactory.java`:

```java
package com.mrsmith.tool;

import com.mrsmith.config.AgentRuntime;
import com.mrsmith.io.IO;
import com.mrsmith.skill.SkillCatalog;

public interface ToolRegistryFactory {

    ToolRegistry create(AgentRuntime runtime, SkillCatalog catalog, IO io, TaskRunner taskRunner);
}
```

- [ ] **Step 6: Update `OpenAiCompatibleProvider`**

In `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java`:

1. Change the import `import com.mrsmith.config.AppConfig;` to `import com.mrsmith.config.AgentRuntime;`.
2. Change `private final AppConfig config;` to `private final AgentRuntime runtime;`.
3. In the three constructors, rename the parameter type/name `AppConfig config` → `AgentRuntime runtime` and `this.config = config;` → `this.runtime = runtime;`.
4. Replace the accessor usages:
   - `config.baseUrl()` (in `send` and `buildRequest`) → `runtime.provider().baseUrl()`
   - `config.apiKey()` → `runtime.provider().apiKey()`
   - `config.model()` → `runtime.agent().model()`
   - `config.includeUsage()` → `runtime.globals().includeUsage()`

- [ ] **Step 7: Update `ChatSession`**

In `src/main/java/com/mrsmith/chat/ChatSession.java`:

1. Change `import com.mrsmith.config.AppConfig;` to `import com.mrsmith.config.AgentRuntime;`.
2. Change `private AppConfig config;` to `private AgentRuntime runtime;`.
3. In `applyAgent()`: `config = agents.resolve(currentAgentName);` → `runtime = agents.resolve(currentAgentName);`; `provider = providerFactory.create(config);` → `providerFactory.create(runtime);`; the `SubAgentRunner.Context` supplier `() -> config` → `() -> runtime`; `toolRegistry = toolRegistryFactory.create(config, skills, io, subAgentRunner);` → `toolRegistryFactory.create(runtime, skills, io, subAgentRunner);`.
4. Replace the accessor usages:
   - `config.maxToolRounds()` → `runtime.agent().maxToolRounds()`
   - `config.maxToolCallsPerSession()` → `runtime.agent().maxToolCallsPerSession()`
   - `config.systemPrompt()` (in `composeSystemPrompt(...)`) → `runtime.agent().systemPrompt()`
   - `config.maxContextTokens()` (four places, in `warnIfNearLimit`/`contextLimitConfigured`/`pctOfMax`/`usageReport`) → `runtime.agent().maxContextTokens()`

- [ ] **Step 8: Update `SubAgentRunner`**

In `src/main/java/com/mrsmith/chat/SubAgentRunner.java`:

1. Change `import com.mrsmith.config.AppConfig;` to `import com.mrsmith.config.AgentRuntime;`.
2. In the `Context` record and the field, change `Supplier<AppConfig> currentConfig` to `Supplier<AgentRuntime> currentConfig` (both occurrences).
3. Change `private AppConfig resolveConfig(String agentName)` to `private AgentRuntime resolveConfig(String agentName)`.
4. Change `context.start(config.systemPrompt());` to `context.start(config.agent().systemPrompt());` (the local `config` is now an `AgentRuntime`).
5. Change the `maxToolRounds` helper:

```java
    private static int maxToolRounds(AppConfig config) {
        Integer value = config.maxToolRounds();
        return value == null ? ToolLoop.DEFAULT_MAX_TOOL_ROUNDS : value;
    }
```

to:

```java
    private static int maxToolRounds(AgentRuntime runtime) {
        Integer value = runtime.agent().maxToolRounds();
        return value == null ? ToolLoop.DEFAULT_MAX_TOOL_ROUNDS : value;
    }
```

and update the call site `maxToolRounds(config)` accordingly.

- [ ] **Step 9: Update `ChatCommand`**

In `src/main/java/com/mrsmith/cli/ChatCommand.java`, change the registry-factory lambda:

```java
                (config, skillCatalog, terminalIo, taskRunner) -> ToolRegistry.with(config.tools(), skillCatalog, terminalIo, taskRunner),
```

to:

```java
                (runtime, skillCatalog, terminalIo, taskRunner) -> ToolRegistry.with(runtime.agent().tools(), skillCatalog, terminalIo, taskRunner),
```

(`OpenAiCompatibleProvider::new` still satisfies `ProviderFactory` because both the interface method and the constructor now take `AgentRuntime`.)

- [ ] **Step 10: Update the test files and delete the obsolete ones**

1. `src/test/java/com/mrsmith/config/AgentCatalogTest.java`:
   - Rename the local `AppConfig config` in `resolveMergesProviderAgentAndGlobals` to `AgentRuntime runtime` and change the assertions to layered accessors; drop the `config.sessionsDir()` assertion (the runtime no longer carries sessions dir):
   ```java
        AgentRuntime runtime = catalog.resolve("coder");
        assertEquals("sk", runtime.provider().apiKey());
        assertEquals("https://example.com/v1", runtime.provider().baseUrl());
        assertEquals("model-x", runtime.agent().model());
        assertEquals("be helpful", runtime.agent().systemPrompt());
        assertEquals(128000, runtime.agent().maxContextTokens());
        assertEquals(false, runtime.globals().includeUsage());
   ```
   - Change `catalog.resolve("coder").tools()` → `catalog.resolve("coder").agent().tools()` (two places: `resolveCarriesToolNames`, `emptyToolsByDefault`).
   - Change `catalog.resolve("coder").maxToolRounds()` → `.agent().maxToolRounds()` (`resolveCarriesMaxToolRounds`).
   - Change `catalog.resolve("coder").maxToolCallsPerSession()` → `.agent().maxToolCallsPerSession()` (two places).

2. `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`:
   - In `loadsProvidersAgentsAndGlobals`, replace the `AppConfig config = catalog.resolve("coder");` block with `AgentRuntime runtime = catalog.resolve("coder");` and layered accessors (`runtime.provider().apiKey()`, `runtime.provider().baseUrl()`, `runtime.agent().model()`, `runtime.agent().systemPrompt()`, `runtime.agent().maxContextTokens()`, `runtime.globals().includeUsage()`).
   - Change `catalog.resolve("a").includeUsage()` → `catalog.resolve("a").globals().includeUsage()` (`defaultsIncludeUsageTrueAndSessionsDirConfigHome`).
   - Change `catalog.resolve("a").tools()` → `catalog.resolve("a").agent().tools()` (two places).
   - Change `catalog.resolve("a").maxToolRounds()` → `catalog.resolve("a").agent().maxToolRounds()` (two places).
   - Change `catalog.resolve("a").maxToolCallsPerSession()` → `catalog.resolve("a").agent().maxToolCallsPerSession()` (two places).
   - Add a new test pinning the preserved trailing-slash behavior:
   ```java
    @Test
    void baseUrlTrailingSlashIsStripped() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "p", "apiKey": "sk-x", "baseUrl": "https://example.com/v1/" } ],
                  "agents": [ { "name": "a", "provider": "p", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals("https://example.com/v1", catalog.resolve("a").provider().baseUrl());
    }
   ```

3. `src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java`:
   - Replace the two `AppConfig` constructions. At the first site (currently `AppConfig config = new AppConfig("sk-test", server.url("/").toString(), "test-model", null); provider = new OpenAiCompatibleProvider(config, HttpClient.newHttpClient(), 0L);`):
   ```java
        AgentRuntime runtime = new AgentRuntime(
                new AgentConfig("a", "p", "test-model", null, null),
                new ProviderConfig("p", "sk-test", server.url("/").toString().replaceAll("/+$", "")),
                new AgentRuntime.Globals(true));
        provider = new OpenAiCompatibleProvider(runtime, HttpClient.newHttpClient(), 0L);
   ```
   At the second site (currently `AppConfig config = new AppConfig("sk-test", server.url("/").toString(), "test-model", null, null, false); provider = new OpenAiCompatibleProvider(config, HttpClient.newHttpClient(), 0L);`):
   ```java
        AgentRuntime runtime = new AgentRuntime(
                new AgentConfig("a", "p", "test-model", null, null),
                new ProviderConfig("p", "sk-test", server.url("/").toString().replaceAll("/+$", "")),
                new AgentRuntime.Globals(false));
        provider = new OpenAiCompatibleProvider(runtime, HttpClient.newHttpClient(), 0L);
   ```
   - Replace `import com.mrsmith.config.AppConfig;` with imports for `AgentConfig`, `AgentRuntime`, and `ProviderConfig`.
   - Note: `server.url("/").toString()` ends in `/`; the `ProviderConfig` compact constructor strips the trailing slash (as the old `AppConfig` constructor did), so the raw URL is passed directly with no test-side normalization.

4. `src/test/java/com/mrsmith/chat/ChatSessionTest.java`:
   - Change `import com.mrsmith.config.AppConfig;` to `import com.mrsmith.config.AgentRuntime;`.
   - Change the `FakeProviderFactory` method to `public Provider create(AgentRuntime runtime) {`.

5. `src/test/java/com/mrsmith/chat/SubAgentRunnerTest.java`:
   - Change `public Provider create(com.mrsmith.config.AppConfig config) {` to `public Provider create(com.mrsmith.config.AgentRuntime runtime) {`.

6. Delete `src/main/java/com/mrsmith/config/AppConfig.java` and `src/test/java/com/mrsmith/config/AppConfigTest.java`:
   ```bash
   git rm src/main/java/com/mrsmith/config/AppConfig.java src/test/java/com/mrsmith/config/AppConfigTest.java
   ```

- [ ] **Step 11: Run the full suite**

Run: `mvn test 2>&1 | grep -E "Tests run: [0-9]+, Failures"`
Expected: BUILD SUCCESS — 308 tests (310 − 4 `AppConfigTest` + 1 `AgentRuntimeTest` + 1 new `ConfigLoaderTest` slash case).

- [ ] **Step 12: Commit**

```bash
git add -A src/main/java src/test/java
git commit -m "refactor: resolve agents to a layered AgentRuntime instead of flat AppConfig"
```

---

### Task 2: Env API keys for providers

**Files:**
- Modify: `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`
- Modify: `README.md`

The `ConfigLoader` env resolution was already added in Task 1's Step 4 (as part of rewriting `parseProviders`). This task pins it with tests and documents it.

- [ ] **Step 1: Write the tests**

Append to `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`:

```java
    @Test
    void providerApiKeyFromEnvOverridesFile() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "opencode", "apiKey": "sk-file", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "opencode", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(),
                Map.of("MRSMITH_OPENCODE_API_KEY", "sk-env"));
        assertEquals("sk-env", catalog.resolve("a").provider().apiKey());
    }

    @Test
    void providerApiKeyFromEnvFillsMissingKey() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "opencode", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "opencode", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(),
                Map.of("MRSMITH_OPENCODE_API_KEY", "sk-env"));
        assertEquals("sk-env", catalog.resolve("a").provider().apiKey());
    }

    @Test
    void providerApiKeyEnvNameNormalizesDashes() throws IOException {
        Path file = writeConfig("""
                {
                  "providers": [ { "name": "my-provider", "baseUrl": "https://example.com/v1" } ],
                  "agents": [ { "name": "a", "provider": "my-provider", "model": "m" } ],
                  "defaultAgent": "a"
                }
                """);
        AgentCatalog catalog = ConfigLoader.load(file, CliConfig.empty(),
                Map.of("MRSMITH_MY_PROVIDER_API_KEY", "sk-env"));
        assertEquals("sk-env", catalog.resolve("a").provider().apiKey());
    }
```

- [ ] **Step 2: Run the tests to verify the behavior**

Run: `mvn -q -Dtest=ConfigLoaderTest test`
Expected: PASS — the three new tests pass because Task 1's Step 4 already added the env resolution. (If you are executing this plan from scratch in order, Task 1 precedes Task 2, so the code is present; if the tests fail, re-check Task 1 Step 4 was applied to `parseProviders`.)

- [ ] **Step 3: Update README**

In `README.md`:

1. In the "Fields" table, update the `providers[].apiKey` row:
   ```markdown
   | `providers[].apiKey` | API key (required unless `MRSMITH_<PROVIDER>_API_KEY` is set) |
   ```
2. In the "Precedence" section, extend the sentence:
   ```markdown
   CLI flags (`--agent`, `--sessions-dir`) > environment variable
   (`MRSMITH_SESSIONS_DIR`) > config file > defaults.
   ```
   to:
   ```markdown
   CLI flags (`--agent`, `--sessions-dir`) > environment variable
   (`MRSMITH_SESSIONS_DIR`) > config file > defaults. Provider API keys are
   read from `MRSMITH_<PROVIDER>_API_KEY` (provider name uppercased, dashes →
   underscores), which overrides the `providers[].apiKey` file value — so keys
   can be kept out of the config file.
   ```

- [ ] **Step 4: Run the full suite**

Run: `mvn test 2>&1 | grep -E "Tests run: [0-9]+, Failures"`
Expected: BUILD SUCCESS — 311 tests.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/mrsmith/config/ConfigLoaderTest.java README.md
git commit -m "feat: source provider API keys from MRSMITH_<PROVIDER>_API_KEY env vars"
```
