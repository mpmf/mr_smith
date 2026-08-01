# Mr Smith — Context Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the user awareness of how much context each turn and the whole session consume (per-turn usage line, `/usage` command, near-limit warnings), using provider-reported usage when available and a local heuristic otherwise.

**Architecture:** The provider already knows exactly what it serializes (system prompt + history) and what it receives back, so it owns usage resolution: it parses `usage` from the SSE stream (request sends `stream_options.include_usage` when enabled) and falls back to a chars/4 heuristic estimate, flagging which case applied. `Provider.send` returns a `ProviderResponse(message, usage, usageEstimated)`. A `UsageTracker` in `ChatSession` accumulates per-turn usage into session totals and formats the per-turn line and `/usage` report; `ChatSession` also warns once at 85% and once at 100% of the configured `maxContextTokens`.

**Tech Stack:** Java 21 · Maven · JUnit 5 · MockWebServer (tests only). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-01-context-management-design.md`

**Design refinements (already reflected in the spec):**
- `ProviderResponse` carries `usageEstimated` so the session never re-estimates.
- Estimation lives in the provider (it knows the exact system prompt + history it sent), not in the session.
- `ChatSession` constructor becomes `(Provider, IO, AppConfig)` so it can read `maxContextTokens` for warnings.
- CLI config values move from positional args to a `CliConfig` record (the old `ConfigLoader.load(...)` positional overloads are replaced).

## File Structure

All paths relative to repo root `/Users/marcoferreira/Projects/mr_smith`.

**New production sources** (`src/main/java/com/mrsmith/`):

| File | Responsibility |
|---|---|
| `provider/TokenEstimator.java` | chars/4 heuristic; static `estimateTokens(String)` |
| `provider/Usage.java` | Record `(Integer promptTokens, Integer completionTokens)` + `total()` |
| `provider/ProviderResponse.java` | Record `(ChatMessage message, Usage usage, boolean usageEstimated)` |
| `provider/SseResult.java` | Record `(String content, Usage usage)` |
| `config/CliConfig.java` | Record bundling CLI overrides; `CliConfig.empty()` |
| `chat/UsageTracker.java` | Accumulates per-turn usage; formats `lastTurnLine()` and `usageReport()` |

**Modified production sources:**

| File | Change |
|---|---|
| `config/AppConfig.java` | Add `Integer maxContextTokens`, `boolean includeUsage`; keep 4-arg convenience ctor |
| `config/ConfigLoader.java` | Use `CliConfig`; read `maxContextTokens`/`includeUsage` |
| `cli/ChatCommand.java` | Add `--max-context`, `--include-usage`; build `CliConfig`; 3-arg `ChatSession` |
| `provider/Provider.java` | `send` returns `ProviderResponse` |
| `provider/SseParser.java` | `consume` returns `SseResult`; parses `usage` |
| `provider/OpenAiCompatibleProvider.java` | `stream_options.include_usage`; resolve usage (real or estimate); return `ProviderResponse` |
| `chat/ChatSession.java` | Track usage per turn, per-turn line, `/usage`, warnings, reset tracker |

**Tests** (new): `provider/TokenEstimatorTest`, `provider/UsageTest`, `config/AppConfigTest`, `chat/UsageTrackerTest`.
**Tests** (modified): `config/ConfigLoaderTest`, `provider/SseParserTest`, `provider/OpenAiCompatibleProviderTest`, `chat/ChatSessionTest`.

## Build & Test Commands

- Compile: `mvn -q compile`
- Test: `mvn -q test`
- Single test class: `mvn -q test -Dtest=ClassName`
- Package (runnable jar): `mvn -q package` → `target/mr-smith.jar`

Current baseline: 35 tests, all green on `master`.

---

### Task 1: TokenEstimator

**Files:**
- Create: `src/main/java/com/mrsmith/provider/TokenEstimator.java`
- Create: `src/test/java/com/mrsmith/provider/TokenEstimatorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.mrsmith.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenEstimatorTest {

    @Test
    void emptyOrNullIsZero() {
        assertEquals(0, TokenEstimator.estimateTokens(""));
        assertEquals(0, TokenEstimator.estimateTokens(null));
    }

    @Test
    void fourCharsAreOneToken() {
        assertEquals(1, TokenEstimator.estimateTokens("abcd"));
    }

    @Test
    void roundsUpPartialToken() {
        assertEquals(2, TokenEstimator.estimateTokens("abcde"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=TokenEstimatorTest`
Expected: FAIL — compilation error, `TokenEstimator` not defined.

- [ ] **Step 3: Implement**

```java
package com.mrsmith.provider;

public final class TokenEstimator {

    private static final int CHARS_PER_TOKEN = 4;

    private TokenEstimator() {
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / (double) CHARS_PER_TOKEN);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=TokenEstimatorTest`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/provider/TokenEstimator.java src/test/java/com/mrsmith/provider/TokenEstimatorTest.java
git commit -m "feat: add TokenEstimator heuristic (chars/4)"
```

---

### Task 2: Usage, ProviderResponse, SseResult Records

**Files:**
- Create: `src/main/java/com/mrsmith/provider/Usage.java`
- Create: `src/main/java/com/mrsmith/provider/ProviderResponse.java`
- Create: `src/main/java/com/mrsmith/provider/SseResult.java`
- Create: `src/test/java/com/mrsmith/provider/UsageTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.mrsmith.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UsageTest {

    @Test
    void totalsSumNonNullFields() {
        assertEquals(1500, new Usage(1200, 300).total());
    }

    @Test
    void totalsHandleNullFields() {
        assertEquals(1200, new Usage(1200, null).total());
        assertEquals(300, new Usage(null, 300).total());
        assertEquals(0, new Usage(null, null).total());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=UsageTest`
Expected: FAIL — compilation error, `Usage` not defined.

- [ ] **Step 3: Implement the three records**

```java
package com.mrsmith.provider;

public record Usage(Integer promptTokens, Integer completionTokens) {

    public int total() {
        int prompt = promptTokens == null ? 0 : promptTokens;
        int completion = completionTokens == null ? 0 : completionTokens;
        return prompt + completion;
    }
}
```

```java
package com.mrsmith.provider;

public record ProviderResponse(ChatMessage message, Usage usage, boolean usageEstimated) {
}
```

```java
package com.mrsmith.provider;

public record SseResult(String content, Usage usage) {
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=UsageTest`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/provider/Usage.java src/main/java/com/mrsmith/provider/ProviderResponse.java src/main/java/com/mrsmith/provider/SseResult.java src/test/java/com/mrsmith/provider/UsageTest.java
git commit -m "feat: add Usage, ProviderResponse, and SseResult records"
```

---

### Task 3: AppConfig New Fields and CliConfig Record

**Files:**
- Modify: `src/main/java/com/mrsmith/config/AppConfig.java`
- Create: `src/main/java/com/mrsmith/config/CliConfig.java`
- Create: `src/test/java/com/mrsmith/config/AppConfigTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.mrsmith.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {

    @Test
    void fourArgConstructorDefaultsIncludeUsageTrueAndMaxContextNull() {
        AppConfig config = new AppConfig("sk", "https://example.com/v1", "gpt", null);
        assertTrue(config.includeUsage());
        assertNull(config.maxContextTokens());
    }

    @Test
    void fullConstructorPreservesValues() {
        AppConfig config = new AppConfig("sk", "https://example.com/v1/", "gpt", "sys", 8192, false);
        assertEquals(8192, config.maxContextTokens());
        assertEquals(false, config.includeUsage());
        assertEquals("https://example.com/v1", config.baseUrl());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=AppConfigTest`
Expected: FAIL — compilation error, `maxContextTokens()` / `includeUsage()` not defined.

- [ ] **Step 3: Modify `AppConfig.java`**

Replace the entire file content with:

```java
package com.mrsmith.config;

import java.util.Objects;

public record AppConfig(String apiKey, String baseUrl, String model, String systemPrompt,
                        Integer maxContextTokens, boolean includeUsage) {

    public AppConfig {
        Objects.requireNonNull(apiKey, "apiKey is required");
        Objects.requireNonNull(baseUrl, "baseUrl is required");
        Objects.requireNonNull(model, "model is required");
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

    public AppConfig(String apiKey, String baseUrl, String model, String systemPrompt) {
        this(apiKey, baseUrl, model, systemPrompt, null, true);
    }
}
```

- [ ] **Step 4: Create `CliConfig.java`**

```java
package com.mrsmith.config;

public record CliConfig(String model, String baseUrl, String systemPrompt, String apiKey,
                        Integer maxContextTokens, Boolean includeUsage) {

    public static CliConfig empty() {
        return new CliConfig(null, null, null, null, null, null);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=AppConfigTest`
Expected: PASS, `BUILD SUCCESS`. The rest of the suite still compiles (4-arg `AppConfig` ctor preserved).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/config/AppConfig.java src/main/java/com/mrsmith/config/CliConfig.java src/test/java/com/mrsmith/config/AppConfigTest.java
git commit -m "feat: add maxContextTokens and includeUsage to AppConfig with CliConfig"
```

---

### Task 4: ConfigLoader Uses CliConfig and Reads New Fields (+ ChatCommand Options)

**Files:**
- Modify: `src/main/java/com/mrsmith/config/ConfigLoader.java`
- Modify: `src/main/java/com/mrsmith/cli/ChatCommand.java`
- Modify: `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`

This task changes `ConfigLoader`'s public API and `ChatCommand` together so the build stays green.

- [ ] **Step 1: Write the failing tests — replace `ConfigLoaderTest.java` content entirely**

```java
package com.mrsmith.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void missingApiKeyFailsFast() {
        assertThrows(ConfigException.class,
                () -> ConfigLoader.load(noFile(), CliConfig.empty(), Map.of()));
    }

    @Test
    void defaultsWhenNoFileEnvOrCli() {
        AppConfig config = ConfigLoader.load(noFile(), CliConfig.empty(), Map.of("OPENAI_API_KEY", "sk-x"));
        assertEquals("gpt-4o-mini", config.model());
        assertEquals("https://api.openai.com/v1", config.baseUrl());
        assertNull(config.systemPrompt());
        assertNull(config.maxContextTokens());
        assertTrue(config.includeUsage());
    }

    @Test
    void envApiKeyIsAccepted() {
        AppConfig config = ConfigLoader.load(noFile(), CliConfig.empty(), Map.of("OPENAI_API_KEY", "sk-env"));
        assertEquals("sk-env", config.apiKey());
    }

    @Test
    void fileApiKeyIsUsedWhenEnvAndCliAbsent() throws IOException {
        Path file = writeConfig("{ \"apiKey\": \"sk-file\" }");
        AppConfig config = ConfigLoader.load(file, CliConfig.empty(), Map.of());
        assertEquals("sk-file", config.apiKey());
    }

    @Test
    void envOverridesFileApiKey() throws IOException {
        Path file = writeConfig("{ \"apiKey\": \"sk-file\" }");
        AppConfig config = ConfigLoader.load(file, CliConfig.empty(), Map.of("OPENAI_API_KEY", "sk-env"));
        assertEquals("sk-env", config.apiKey());
    }

    @Test
    void envOverridesFile() throws IOException {
        Path file = writeConfig("{ \"model\": \"from-file\", \"baseUrl\": \"https://file.example\", \"systemPrompt\": \"file prompt\" }");
        AppConfig config = ConfigLoader.load(file, CliConfig.empty(),
                Map.of("OPENAI_API_KEY", "sk-env", "MRSMITH_MODEL", "from-env"));
        assertEquals("from-env", config.model());
        assertEquals("https://file.example", config.baseUrl());
        assertEquals("file prompt", config.systemPrompt());
    }

    @Test
    void cliOverridesEnv() throws IOException {
        Path file = writeConfig("{ \"model\": \"from-file\" }");
        CliConfig cli = new CliConfig("from-cli", null, null, "sk-cli", null, null);
        AppConfig config = ConfigLoader.load(file, cli,
                Map.of("OPENAI_API_KEY", "sk-env", "MRSMITH_MODEL", "from-env"));
        assertEquals("from-cli", config.model());
        assertEquals("sk-cli", config.apiKey());
    }

    @Test
    void malformedFileFallsBackToDefaults() throws IOException {
        Path file = writeConfig("not valid json {{{");
        AppConfig config = ConfigLoader.load(file, CliConfig.empty(), Map.of("OPENAI_API_KEY", "sk-x"));
        assertEquals("gpt-4o-mini", config.model());
        assertEquals("sk-x", config.apiKey());
    }

    @Test
    void baseUrlTrailingSlashIsStripped() throws IOException {
        Path file = writeConfig("{ \"baseUrl\": \"https://example.com/v1/\" }");
        AppConfig config = ConfigLoader.load(file, CliConfig.empty(), Map.of("OPENAI_API_KEY", "sk-x"));
        assertEquals("https://example.com/v1", config.baseUrl());
    }

    @Test
    void maxContextAndIncludeUsageReadFromFile() throws IOException {
        Path file = writeConfig("{ \"maxContextTokens\": 128000, \"includeUsage\": false }");
        AppConfig config = ConfigLoader.load(file, CliConfig.empty(), Map.of("OPENAI_API_KEY", "sk-x"));
        assertEquals(128000, config.maxContextTokens());
        assertFalse(config.includeUsage());
    }

    @Test
    void cliOverridesFileForMaxContextAndIncludeUsage() throws IOException {
        Path file = writeConfig("{ \"maxContextTokens\": 128000, \"includeUsage\": false }");
        CliConfig cli = new CliConfig(null, null, null, null, 8192, true);
        AppConfig config = ConfigLoader.load(file, cli, Map.of("OPENAI_API_KEY", "sk-x"));
        assertEquals(8192, config.maxContextTokens());
        assertTrue(config.includeUsage());
    }

    @Test
    void envProvidesMaxContextAndIncludeUsage() throws IOException {
        AppConfig config = ConfigLoader.load(noFile(), CliConfig.empty(),
                Map.of("OPENAI_API_KEY", "sk-x", "MRSMITH_MAX_CONTEXT", "8192", "MRSMITH_INCLUDE_USAGE", "false"));
        assertEquals(8192, config.maxContextTokens());
        assertFalse(config.includeUsage());
    }

    @Test
    void invalidEnvMaxContextIsIgnored() throws IOException {
        AppConfig config = ConfigLoader.load(noFile(), CliConfig.empty(),
                Map.of("OPENAI_API_KEY", "sk-x", "MRSMITH_MAX_CONTEXT", "abc"));
        assertNull(config.maxContextTokens());
    }

    private Path noFile() {
        return tempDir.resolve("missing.json");
    }

    private Path writeConfig(String content) throws IOException {
        Path file = tempDir.resolve("config.json");
        Files.writeString(file, content);
        return file;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ConfigLoaderTest`
Expected: FAIL — compilation errors: `ConfigLoader.load` old signature gone; `CliConfig` still exists but `ConfigLoader` hasn't been updated.

- [ ] **Step 3: Replace `ConfigLoader.java` content entirely**

```java
package com.mrsmith.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class ConfigLoader {

    public static final Path DEFAULT_CONFIG_PATH = Path.of(
            System.getProperty("user.home"), ".config", "mrsmith", "config.json");

    private static final ObjectMapper JSON = new ObjectMapper();

    private ConfigLoader() {
    }

    public static AppConfig load(CliConfig cli) {
        return load(DEFAULT_CONFIG_PATH, cli, System.getenv());
    }

    public static AppConfig load(Path configFile, CliConfig cli, Map<String, String> env) {
        String fileModel = null;
        String fileBaseUrl = null;
        String fileSystemPrompt = null;
        String fileApiKey = null;
        Integer fileMaxContext = null;
        Boolean fileIncludeUsage = null;

        if (Files.exists(configFile)) {
            try {
                JsonNode root = JSON.readTree(configFile.toFile());
                if (root.hasNonNull("model")) {
                    fileModel = root.get("model").asText();
                }
                if (root.hasNonNull("baseUrl")) {
                    fileBaseUrl = root.get("baseUrl").asText();
                }
                if (root.hasNonNull("systemPrompt")) {
                    fileSystemPrompt = root.get("systemPrompt").asText();
                }
                if (root.hasNonNull("apiKey")) {
                    fileApiKey = root.get("apiKey").asText();
                }
                if (root.hasNonNull("maxContextTokens")) {
                    fileMaxContext = root.get("maxContextTokens").asInt();
                }
                if (root.hasNonNull("includeUsage")) {
                    fileIncludeUsage = root.get("includeUsage").asBoolean();
                }
            } catch (IOException e) {
                System.err.println("Warning: could not read config file " + configFile
                        + " (" + e.getMessage() + "). Falling back to env vars and defaults.");
            }
        }

        String model = firstNonNull(cli.model(), env.get("MRSMITH_MODEL"), fileModel, "gpt-4o-mini");
        String baseUrl = firstNonNull(cli.baseUrl(), env.get("MRSMITH_BASE_URL"), fileBaseUrl,
                "https://api.openai.com/v1");
        String systemPrompt = firstNonNull(cli.systemPrompt(), fileSystemPrompt);
        String apiKey = firstNonNull(cli.apiKey(), env.get("OPENAI_API_KEY"), fileApiKey);

        Integer maxContext = firstNonNullValue(cli.maxContextTokens(),
                parseEnvInt(env.get("MRSMITH_MAX_CONTEXT")), fileMaxContext);
        Boolean includeUsage = firstNonNullValue(cli.includeUsage(),
                parseEnvBool(env.get("MRSMITH_INCLUDE_USAGE")), fileIncludeUsage);

        if (apiKey == null) {
            throw new ConfigException(
                    "OPENAI_API_KEY is not set. Export it as an environment variable "
                            + "(e.g. export OPENAI_API_KEY=sk-...) or pass it with --api-key.");
        }

        return new AppConfig(apiKey, baseUrl, model, systemPrompt,
                maxContext, includeUsage == null || includeUsage);
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static <T> T firstNonNullValue(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer parseEnvInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean parseEnvBool(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Boolean.parseBoolean(value);
    }
}
```

- [ ] **Step 4: Modify `ChatCommand.java` to build a `CliConfig` and add the two options**

Replace the options block and `call()`:

```java
    @Option(names = "--model", description = "Model to use (overrides config file and env).")
    private String model;

    @Option(names = "--base-url", description = "Provider base URL, e.g. https://api.openai.com/v1")
    private String baseUrl;

    @Option(names = "--system-prompt", description = "Optional system prompt.")
    private String systemPrompt;

    @Option(names = "--api-key", description = "API key (overrides OPENAI_API_KEY).")
    private String apiKey;

    @Option(names = "--max-context", description = "Context window token limit (overrides config file and env).")
    private Integer maxContext;

    @Option(names = "--include-usage", description = "Request usage stats from the provider (default true).")
    private Boolean includeUsage;

    @Override
    public Integer call() {
        AppConfig config;
        try {
            config = ConfigLoader.load(
                    new CliConfig(apiKey, baseUrl, model, systemPrompt, maxContext, includeUsage));
        } catch (ConfigException e) {
            System.err.println(e.getMessage());
            return 1;
        }

        Provider provider = new OpenAiCompatibleProvider(config);
        IO io = new ReplIo();
        ChatSession session = new ChatSession(provider, io);
        try {
            session.run();
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
        return 0;
    }
```

Add the import: `import com.mrsmith.config.CliConfig;` (the `ConfigLoader` import already exists).

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=ConfigLoaderTest`
Expected: PASS — 13 tests, `BUILD SUCCESS`.

- [ ] **Step 6: Run the full suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` — all tests green (baseline 35, plus the AppConfig tests and the new ConfigLoader tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mrsmith/config/ConfigLoader.java src/main/java/com/mrsmith/cli/ChatCommand.java src/test/java/com/mrsmith/config/ConfigLoaderTest.java
git commit -m "feat: read maxContextTokens and includeUsage with CliConfig"
```

---

### Task 5: SseParser Returns SseResult

**Files:**
- Modify: `src/main/java/com/mrsmith/provider/SseParser.java`
- Modify: `src/test/java/com/mrsmith/provider/SseParserTest.java`

- [ ] **Step 1: Replace `SseParserTest.java` content entirely**

```java
package com.mrsmith.provider;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SseParserTest {

    @Test
    void extractsDeltasInOrder() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"content":"Hello"}}]}

                data: {"choices":[{"delta":{"content":" world"}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add);
        assertEquals("Hello world", result.content());
        assertEquals(List.of("Hello", " world"), deltas);
        assertNull(result.usage());
    }

    @Test
    void ignoresChunksWithoutContent() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"role":"assistant"}}]}

                data: {"choices":[{"delta":{"content":"hi"}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add);
        assertEquals("hi", result.content());
        assertEquals(List.of("hi"), deltas);
    }

    @Test
    void usesPartialTextWhenStreamEndsWithoutDone() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"content":"partial"}}]}
                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add);
        assertEquals("partial", result.content());
    }

    @Test
    void skipsMalformedLinesAndContinues() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: not-json

                data: {"choices":[{"delta":{"content":"ok"}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add);
        assertEquals("ok", result.content());
        assertEquals(List.of("ok"), deltas);
    }

    @Test
    void extractsUsageFromChunk() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"content":"hi"}}]}

                data: {"usage":{"prompt_tokens":1200,"completion_tokens":300}}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add);
        assertEquals("hi", result.content());
        assertEquals(new Usage(1200, 300), result.usage());
    }

    @Test
    void malformedUsageDoesNotBreakStream() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"usage":"oops","choices":[{"delta":{"content":"hi"}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add);
        assertEquals("hi", result.content());
        assertNull(result.usage());
    }

    @Test
    void emptyUsageObjectYieldsNullUsage() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"usage":{}}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add);
        assertEquals("", result.content());
        assertNull(result.usage());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=SseParserTest`
Expected: FAIL — compilation errors: `SseResult` used but `consume` still returns `String`.

- [ ] **Step 3: Replace `SseParser.java` content entirely**

```java
package com.mrsmith.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.function.Consumer;

public final class SseParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SseParser() {
    }

    public static SseResult consume(BufferedReader reader, Consumer<String> deltaSink) throws IOException {
        StringBuilder full = new StringBuilder();
        Usage usage = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring("data:".length()).trim();
            if (payload.equals("[DONE]")) {
                break;
            }
            if (payload.isEmpty()) {
                continue;
            }
            JsonNode node = parse(payload);
            if (node == null) {
                continue;
            }
            Usage chunkUsage = extractUsage(node);
            if (chunkUsage != null) {
                usage = chunkUsage;
            }
            JsonNode delta = node.path("choices").path(0).path("delta");
            if (!delta.isMissingNode()) {
                String content = delta.path("content").asText(null);
                if (content != null && !content.isEmpty()) {
                    deltaSink.accept(content);
                    full.append(content);
                }
            }
        }
        return new SseResult(full.toString(), usage);
    }

    private static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (IOException e) {
            System.err.println("Warning: malformed SSE chunk, skipping: " + payload);
            return null;
        }
    }

    private static Usage extractUsage(JsonNode node) {
        JsonNode usageNode = node.path("usage");
        if (usageNode.isMissingNode() || usageNode.isNull()) {
            return null;
        }
        Integer prompt = usageNode.hasNonNull("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : null;
        Integer completion = usageNode.hasNonNull("completion_tokens") ? usageNode.get("completion_tokens").asInt() : null;
        if (prompt == null && completion == null) {
            return null;
        }
        return new Usage(prompt, completion);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=SseParserTest`
Expected: PASS — 7 tests, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/provider/SseParser.java src/test/java/com/mrsmith/provider/SseParserTest.java
git commit -m "feat: SseParser returns content and parsed usage"
```

---

### Task 6: Provider Returns ProviderResponse with Usage Resolution

**Files:**
- Modify: `src/main/java/com/mrsmith/provider/Provider.java`
- Modify: `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java`
- Modify: `src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java`
- Modify: `src/test/java/com/mrsmith/chat/ChatSessionTest.java` (FakeProvider only — one line)
- Modify: `src/main/java/com/mrsmith/chat/ChatSession.java` (minimal compile fix — one line)

- [ ] **Step 1: Change the `Provider` interface**

`src/main/java/com/mrsmith/provider/Provider.java`:

```java
package com.mrsmith.provider;

import java.util.List;
import java.util.function.Consumer;

public interface Provider {

    ProviderResponse send(List<ChatMessage> history, Consumer<String> tokenSink);
}
```

- [ ] **Step 2: Update `OpenAiCompatibleProviderTest.java`**

Make these changes:

1. In `streamsTokensAndReturnsFullMessage`, replace the `send`/asserts with:

```java
        ProviderResponse response = provider.send(List.of(new ChatMessage(Role.USER, "hello")), deltas::add);
        assertEquals("Hi there", response.message().content());
        assertEquals(List.of("Hi", " there"), deltas);
        assertNotNull(response.usage());
        assertTrue(response.usageEstimated());
```

2. In `sendsCorrectRequestBodyAndAuth`, add a `stream_options` assertion after the `"stream":true` one:

```java
        assertTrue(body.contains("\"stream_options\":{\"include_usage\":true}"));
```

3. In `retriesOnceOn5xxThenSucceeds`, replace the reply assignment with:

```java
        ProviderResponse response = provider.send(List.of(new ChatMessage(Role.USER, "hi")), deltas::add);
        assertEquals("ok", response.message().content());
```

4. In `retriesOnNetworkFailureThenSucceeds`, replace the reply assignment with:

```java
        ProviderResponse response = provider.send(List.of(new ChatMessage(Role.USER, "hi")), deltas::add);
        assertEquals("ok", response.message().content());
```

5. Add these three new tests at the end of the class (before the closing brace):

```java
    @Test
    void usesRealUsageWhenProviderSendsIt() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"delta":{"content":"ok"}}]}

                        data: {"usage":{"prompt_tokens":1200,"completion_tokens":300}}

                        data: [DONE]

                        """));
        ProviderResponse response = provider.send(List.of(new ChatMessage(Role.USER, "hi")), s -> { });
        assertEquals("ok", response.message().content());
        assertEquals(new Usage(1200, 300), response.usage());
        assertFalse(response.usageEstimated());
    }

    @Test
    void estimatesUsageWhenProviderSendsNone() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"));
        ProviderResponse response = provider.send(List.of(new ChatMessage(Role.USER, "hi")), s -> { });
        assertTrue(response.usageEstimated());
        assertTrue(response.usage().promptTokens() >= 1);
        assertEquals(1, response.usage().completionTokens());
    }

    @Test
    void includeUsageDisabledOmitsStreamOptions() throws Exception {
        server.shutdown();
        server = new MockWebServer();
        server.start();
        AppConfig config = new AppConfig("sk-test", server.url("/").toString(), "test-model", null, null, false);
        provider = new OpenAiCompatibleProvider(config, HttpClient.newHttpClient(), 0L);
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        provider.send(List.of(new ChatMessage(Role.USER, "hello")), s -> { });
        RecordedRequest request = server.takeRequest();
        assertFalse(request.getBody().readUtf8().contains("stream_options"));
    }
```

Add the missing imports to the test file: `import static org.junit.jupiter.api.Assertions.assertFalse;`, `import static org.junit.jupiter.api.Assertions.assertNotNull;`, and `import static org.junit.jupiter.api.Assertions.assertNull;` (the file already imports `assertEquals`, `assertThrows`, `assertTrue`).

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q test -Dtest=OpenAiCompatibleProviderTest`
Expected: FAIL — compilation errors (provider still returns `ChatMessage`; `ChatSessionTest` also broken until Step 4 fixes its fake).

- [ ] **Step 4: Fix the compile breaks downstream**

In `src/main/java/com/mrsmith/chat/ChatSession.java`, change the `send` call line to:

```java
                ChatMessage reply = provider.send(history, io::write).message();
```

In `src/test/java/com/mrsmith/chat/ChatSessionTest.java`, update the `FakeProvider.send` method to return a `ProviderResponse`:

```java
        @Override
        public ProviderResponse send(List<ChatMessage> history, Consumer<String> tokenSink) {
            receivedHistories.add(new ArrayList<>(history));
            calls++;
            ChatMessage last = history.get(history.size() - 1);
            String reply = last.content() + " response";
            tokenSink.accept(reply);
            return new ProviderResponse(new ChatMessage(Role.ASSISTANT, reply), null, false);
        }
```

The lambda providers (`failing`, `interrupted`) in the tests only `throw`, which still satisfies the new return type; `FirstThenProvider.send` delegates to `first.send(...)` / `then.send(...)` so it needs no change.

- [ ] **Step 5: Implement the provider changes in `OpenAiCompatibleProvider.java`**

Replace the `send`, `handleResponse`, and `buildRequestBody` methods, and add `estimateUsage`. The full updated class:

```java
package com.mrsmith.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mrsmith.config.AppConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

public class OpenAiCompatibleProvider implements Provider {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AppConfig config;
    private final HttpClient httpClient;
    private final long retryDelayMillis;

    public OpenAiCompatibleProvider(AppConfig config) {
        this(config, HttpClient.newHttpClient(), 2000L);
    }

    OpenAiCompatibleProvider(AppConfig config, HttpClient httpClient) {
        this(config, httpClient, 2000L);
    }

    OpenAiCompatibleProvider(AppConfig config, HttpClient httpClient, long retryDelayMillis) {
        this.config = config;
        this.httpClient = httpClient;
        this.retryDelayMillis = retryDelayMillis;
    }

    @Override
    public ProviderResponse send(List<ChatMessage> history, Consumer<String> tokenSink) {
        try {
            return doSend(history, tokenSink);
        } catch (ProviderException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Network error while contacting "
                    + config.baseUrl() + ": " + e.getMessage(), e);
        }
    }

    private ProviderResponse doSend(List<ChatMessage> history, Consumer<String> tokenSink)
            throws IOException, InterruptedException {
        HttpRequest request = buildRequest(buildRequestBody(history));
        HttpResponse<InputStream> response = sendWithRetry(request);
        return handleResponse(response, history, tokenSink);
    }

    private HttpResponse<InputStream> sendWithRetry(HttpRequest request)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            Thread.sleep(retryDelayMillis);
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        }
        if (response.statusCode() >= 500) {
            response.body().close();
            Thread.sleep(retryDelayMillis);
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        }
        return response;
    }

    private ProviderResponse handleResponse(HttpResponse<InputStream> response, List<ChatMessage> history,
                                            Consumer<String> tokenSink) {
        if (response.statusCode() >= 500) {
            throw new ProviderException("Provider error HTTP " + response.statusCode()
                    + " after retry: " + errorBody(response));
        }
        if (response.statusCode() >= 400) {
            throw new ProviderException("Provider error HTTP " + response.statusCode()
                    + ": " + errorBody(response));
        }
        StringBuilder partial = new StringBuilder();
        Consumer<String> sink = delta -> {
            tokenSink.accept(delta);
            partial.append(delta);
        };
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
            SseResult result = SseParser.consume(reader, sink);
            ChatMessage message = new ChatMessage(Role.ASSISTANT, result.content());
            Usage usage = result.usage();
            boolean estimated = false;
            if (usage == null) {
                usage = estimateUsage(history, result.content());
                estimated = true;
            }
            return new ProviderResponse(message, usage, estimated);
        } catch (IOException e) {
            String text = partial.isEmpty() ? null : partial.toString();
            throw new ProviderException(text == null
                    ? "Network error during request: " + e.getMessage()
                    : "Stream interrupted: " + e.getMessage(), e, text);
        }
    }

    private Usage estimateUsage(List<ChatMessage> history, String replyContent) {
        int prompt = 0;
        if (config.systemPrompt() != null) {
            prompt += TokenEstimator.estimateTokens(config.systemPrompt());
        }
        for (ChatMessage message : history) {
            prompt += TokenEstimator.estimateTokens(message.content());
        }
        return new Usage(prompt, TokenEstimator.estimateTokens(replyContent));
    }

    private String buildRequestBody(List<ChatMessage> history) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);
        if (config.includeUsage()) {
            root.putObject("stream_options").put("include_usage", true);
        }
        ArrayNode messages = root.putArray("messages");
        if (config.systemPrompt() != null) {
            messages.addObject()
                    .put("role", Role.SYSTEM.apiName())
                    .put("content", config.systemPrompt());
        }
        for (ChatMessage message : history) {
            messages.addObject()
                    .put("role", message.roleName())
                    .put("content", message.content());
        }
        try {
            return JSON.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize request body", e);
        }
    }

    private HttpRequest buildRequest(String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/chat/completions"))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static String errorBody(HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(unable to read error body: " + e.getMessage() + ")";
        }
    }
}
```

- [ ] **Step 6: Run the affected tests to verify they pass**

Run: `mvn -q test -Dtest=OpenAiCompatibleProviderTest,ChatSessionTest`
Expected: PASS — 8 provider tests + 7 session tests, `BUILD SUCCESS`.

- [ ] **Step 7: Run the full suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` — all tests green.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/mrsmith/provider/Provider.java src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java src/main/java/com/mrsmith/chat/ChatSession.java src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java src/test/java/com/mrsmith/chat/ChatSessionTest.java
git commit -m "feat: provider resolves usage (real or estimate) and returns ProviderResponse"
```

---

### Task 7: UsageTracker

**Files:**
- Create: `src/main/java/com/mrsmith/chat/UsageTracker.java`
- Create: `src/test/java/com/mrsmith/chat/UsageTrackerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.mrsmith.chat;

import com.mrsmith.provider.Usage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageTrackerTest {

    @Test
    void accumulatesTurns() {
        UsageTracker tracker = new UsageTracker();
        tracker.recordTurn(new Usage(1200, 300), false);
        tracker.recordTurn(new Usage(800, 200), false);
        assertEquals(2000, tracker.promptTokens());
        assertEquals(500, tracker.completionTokens());
        assertEquals(2500, tracker.totalTokens());
        assertFalse(tracker.sessionEstimated());
    }

    @Test
    void lastTurnLineFormatsWithGrouping() {
        UsageTracker tracker = new UsageTracker();
        tracker.recordTurn(new Usage(1234, 345), false);
        assertEquals("tokens: 1,234 in · 345 out · total 1,579 · session 1,579",
                tracker.lastTurnLine());
    }

    @Test
    void estimatedTurnsAreFlagged() {
        UsageTracker tracker = new UsageTracker();
        tracker.recordTurn(new Usage(100, 50), true);
        assertEquals("tokens: 100 in (est.) · 50 out (est.) · total 150 · session 150 (est.)",
                tracker.lastTurnLine());
        assertTrue(tracker.sessionEstimated());
    }

    @Test
    void sessionEstimatedStaysTrueAfterRealTurn() {
        UsageTracker tracker = new UsageTracker();
        tracker.recordTurn(new Usage(100, 50), true);
        tracker.recordTurn(new Usage(100, 50), false);
        assertEquals("tokens: 100 in · 50 out · total 150 · session 300 (est.)",
                tracker.lastTurnLine());
    }

    @Test
    void usageReportListsTotals() {
        UsageTracker tracker = new UsageTracker();
        tracker.recordTurn(new Usage(12000, 3456), false);
        assertEquals("Session usage:\n  prompt:      12,000\n  completion:   3,456\n  total:      15,456",
                tracker.usageReport());
    }

    @Test
    void usageReportFlagsEstimatedSession() {
        UsageTracker tracker = new UsageTracker();
        tracker.recordTurn(new Usage(100, 50), true);
        assertEquals("Session usage:\n  prompt:      100\n  completion:     50\n  total:        150 (est.)",
                tracker.usageReport());
    }

    @Test
    void nullUsageIsIgnored() {
        UsageTracker tracker = new UsageTracker();
        tracker.recordTurn(null, false);
        assertEquals(0, tracker.totalTokens());
        assertEquals("", tracker.lastTurnLine());
    }

    @Test
    void resetClearsTotals() {
        UsageTracker tracker = new UsageTracker();
        tracker.recordTurn(new Usage(100, 50), true);
        tracker.reset();
        assertEquals(0, tracker.totalTokens());
        assertFalse(tracker.sessionEstimated());
        assertEquals("", tracker.lastTurnLine());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=UsageTrackerTest`
Expected: FAIL — compilation error, `UsageTracker` not defined.

- [ ] **Step 3: Implement**

```java
package com.mrsmith.chat;

import com.mrsmith.provider.Usage;

import java.util.Locale;

public class UsageTracker {

    private int promptTokens;
    private int completionTokens;
    private boolean sessionEstimated;
    private Usage lastTurn;
    private boolean lastTurnEstimated;

    public void recordTurn(Usage usage, boolean estimated) {
        if (usage == null) {
            return;
        }
        lastTurn = usage;
        lastTurnEstimated = estimated;
        if (estimated) {
            sessionEstimated = true;
        }
        if (usage.promptTokens() != null) {
            promptTokens += usage.promptTokens();
        }
        if (usage.completionTokens() != null) {
            completionTokens += usage.completionTokens();
        }
    }

    public String lastTurnLine() {
        if (lastTurn == null) {
            return "";
        }
        int in = lastTurn.promptTokens() == null ? 0 : lastTurn.promptTokens();
        int out = lastTurn.completionTokens() == null ? 0 : lastTurn.completionTokens();
        String turnEst = lastTurnEstimated ? " (est.)" : "";
        String sessionEst = sessionEstimated ? " (est.)" : "";
        return String.format(Locale.US,
                "tokens: %,d in%s · %,d out%s · total %,d · session %,d%s",
                in, turnEst, out, turnEst, in + out, totalTokens(), sessionEst);
    }

    public String usageReport() {
        String est = sessionEstimated ? " (est.)" : "";
        return String.format(Locale.US,
                "Session usage:%n  prompt:      %,d%n  completion:  %,d%n  total:       %,d%s",
                promptTokens, completionTokens, totalTokens(), est);
    }

    public int promptTokens() {
        return promptTokens;
    }

    public int completionTokens() {
        return completionTokens;
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public boolean sessionEstimated() {
        return sessionEstimated;
    }

    public void reset() {
        promptTokens = 0;
        completionTokens = 0;
        sessionEstimated = false;
        lastTurn = null;
        lastTurnEstimated = false;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=UsageTrackerTest`
Expected: PASS — 8 tests, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/chat/UsageTracker.java src/test/java/com/mrsmith/chat/UsageTrackerTest.java
git commit -m "feat: add UsageTracker for per-turn and session usage"
```

---

### Task 8: ChatSession Usage Features

**Files:**
- Modify: `src/main/java/com/mrsmith/chat/ChatSession.java`
- Modify: `src/test/java/com/mrsmith/chat/ChatSessionTest.java`
- Modify: `src/main/java/com/mrsmith/cli/ChatCommand.java` (constructor call)

- [ ] **Step 1: Replace `ChatSessionTest.java` content entirely**

```java
package com.mrsmith.chat;

import com.mrsmith.config.AppConfig;
import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderException;
import com.mrsmith.provider.ProviderResponse;
import com.mrsmith.provider.Role;
import com.mrsmith.provider.Usage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSessionTest {

    @Test
    void sendsUserMessageAndStoresReplyInHistory() throws Exception {
        FakeProvider provider = new FakeProvider();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(provider, io, config());
        session.run();
        assertEquals(1, provider.receivedHistories.get(0).size());
        assertEquals(Role.USER, provider.receivedHistories.get(0).get(0).role());
        assertEquals("hello", provider.receivedHistories.get(0).get(0).content());
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("hello response")));
    }

    @Test
    void keepsContextAcrossTurns() throws Exception {
        FakeProvider provider = new FakeProvider();
        StubIo io = new StubIo(List.of("first", "second", "/exit"));
        ChatSession session = new ChatSession(provider, io, config());
        session.run();
        assertEquals(2, provider.receivedHistories.size());
        List<ChatMessage> secondTurn = provider.receivedHistories.get(1);
        assertEquals(3, secondTurn.size());
        assertEquals("first", secondTurn.get(0).content());
        assertEquals("first response", secondTurn.get(1).content());
        assertEquals("second", secondTurn.get(2).content());
    }

    @Test
    void resetClearsHistory() throws Exception {
        FakeProvider provider = new FakeProvider();
        StubIo io = new StubIo(List.of("first", "/reset", "second", "/exit"));
        ChatSession session = new ChatSession(provider, io, config());
        session.run();
        List<ChatMessage> secondTurn = provider.receivedHistories.get(1);
        assertEquals(1, secondTurn.size());
        assertEquals("second", secondTurn.get(0).content());
    }

    @Test
    void unknownCommandIsNotSentToProvider() throws Exception {
        FakeProvider provider = new FakeProvider();
        StubIo io = new StubIo(List.of("/bogus", "/exit"));
        ChatSession session = new ChatSession(provider, io, config());
        session.run();
        assertTrue(provider.receivedHistories.isEmpty());
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("Unknown command")));
    }

    @Test
    void providerErrorIsShownAndLoopContinues() throws Exception {
        Provider failing = (history, sink) -> {
            throw new ProviderException("HTTP 401: bad key");
        };
        FakeProvider ok = new FakeProvider();
        StubIo io = new StubIo(List.of("hello", "again", "/exit"));
        ChatSession session = new ChatSession(new FirstThenProvider(failing, ok), io, config());
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("HTTP 401")));
        assertEquals(1, ok.calls);
    }

    @Test
    void partialContentFromInterruptedStreamIsKeptInHistory() throws Exception {
        Provider interrupted = (history, sink) -> {
            sink.accept("partial");
            throw new ProviderException("Stream interrupted", null, "partial");
        };
        FakeProvider ok = new FakeProvider();
        StubIo io = new StubIo(List.of("hello", "again", "/exit"));
        ChatSession session = new ChatSession(new FirstThenProvider(interrupted, ok), io, config());
        session.run();
        List<ChatMessage> secondTurn = ok.receivedHistories.get(0);
        assertEquals(3, secondTurn.size());
        assertEquals(Role.ASSISTANT, secondTurn.get(1).role());
        assertEquals("partial", secondTurn.get(1).content());
    }

    @Test
    void genericProviderFailureIsShownAndLoopContinues() throws Exception {
        Provider failing = (history, sink) -> {
            throw new IllegalStateException("boom");
        };
        FakeProvider ok = new FakeProvider();
        StubIo io = new StubIo(List.of("hello", "again", "/exit"));
        ChatSession session = new ChatSession(new FirstThenProvider(failing, ok), io, config());
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("boom")));
        assertEquals(1, ok.calls);
    }

    @Test
    void printsPerTurnUsageLine() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(1200, 300), false);
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(provider, io, config());
        session.run();
        assertTrue(io.lines.contains("tokens: 1,200 in · 300 out · total 1,500 · session 1,500"));
    }

    @Test
    void usageLineFlagsEstimates() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(100, 50), true);
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(provider, io, config());
        session.run();
        assertTrue(io.lines.contains("tokens: 100 in (est.) · 50 out (est.) · total 150 · session 150 (est.)"));
    }

    @Test
    void usageCommandPrintsReport() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(1200, 300), false);
        StubIo io = new StubIo(List.of("hello", "/usage", "/exit"));
        ChatSession session = new ChatSession(provider, io, config());
        session.run();
        assertTrue(io.lines.contains("Session usage:"));
        assertTrue(io.lines.contains("  total:       1,500"));
        assertTrue(io.lines.contains("  history: 1 messages"));
    }

    @Test
    void warnsAtEightyFiveAndHundredPercent() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(900, 0), false);
        StubIo io = new StubIo(List.of("hello", "again", "once more", "/exit"));
        ChatSession session = new ChatSession(provider, io, config(1000));
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("session at 90% of your configured 1,000-token context limit")));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("session reached 100% of your configured 1,000-token context limit")));
    }

    @Test
    void warnsOncePerThreshold() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(900, 0), false);
        StubIo io = new StubIo(List.of("a", "b", "c", "d", "e", "/exit"));
        ChatSession session = new ChatSession(provider, io, config(1000));
        session.run();
        long warnings = io.lines.stream().filter(l -> l.startsWith("Warning:")).count();
        assertEquals(2, warnings);
    }

    @Test
    void resetClearsUsageTracker() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(900, 0), false);
        StubIo io = new StubIo(List.of("hello", "/reset", "/usage", "/exit"));
        ChatSession session = new ChatSession(provider, io, config(1000));
        session.run();
        assertTrue(io.lines.contains("  total:       0"));
        assertTrue(io.lines.contains("  history: 0 messages"));
    }

    @Test
    void usageReportShowsContextLimitWhenConfigured() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(1200, 300), false);
        StubIo io = new StubIo(List.of("hello", "/usage", "/exit"));
        ChatSession session = new ChatSession(provider, io, config(128000));
        session.run();
        assertTrue(io.lines.contains("  context limit: 128,000 configured (1% used)"));
    }

    private AppConfig config() {
        return config(null);
    }

    private AppConfig config(Integer maxContextTokens) {
        return new AppConfig("sk-test", "https://example.com/v1", "test-model", null,
                maxContextTokens, true);
    }

    static class StubIo implements IO {
        final Deque<String> inputs;
        final List<String> lines = new ArrayList<>();

        StubIo(List<String> inputs) {
            this.inputs = new ArrayDeque<>(inputs);
        }

        @Override
        public String readLine() throws IOException {
            return inputs.poll();
        }

        @Override
        public void write(String text) {
            lines.add(text);
        }

        @Override
        public void writeLine(String line) {
            lines.add(line);
        }
    }

    static class FakeProvider implements Provider {
        final Usage turnUsage;
        final boolean estimated;
        final List<List<ChatMessage>> receivedHistories = new ArrayList<>();
        int calls = 0;

        FakeProvider() {
            this(new Usage(0, 0), false);
        }

        FakeProvider(Usage turnUsage, boolean estimated) {
            this.turnUsage = turnUsage;
            this.estimated = estimated;
        }

        @Override
        public ProviderResponse send(List<ChatMessage> history, Consumer<String> tokenSink) {
            receivedHistories.add(new ArrayList<>(history));
            calls++;
            ChatMessage last = history.get(history.size() - 1);
            String reply = last.content() + " response";
            tokenSink.accept(reply);
            return new ProviderResponse(new ChatMessage(Role.ASSISTANT, reply), turnUsage, estimated);
        }
    }

    static class FirstThenProvider implements Provider {
        final Provider first;
        final Provider then;
        int calls = 0;

        FirstThenProvider(Provider first, Provider then) {
            this.first = first;
            this.then = then;
        }

        @Override
        public ProviderResponse send(List<ChatMessage> history, Consumer<String> tokenSink) {
            if (calls++ == 0) {
                return first.send(history, tokenSink);
            }
            return then.send(history, tokenSink);
        }
    }
}
```

Note: `warnsAtEightyFiveAndHundredPercent` with `config(1000)` and turns of 900 tokens each: turn 1 → 90% (warn 85), turn 2 → 180% (warn 100), turn 3 → 270% (no new warning). `warnsOncePerThreshold` confirms only 2 warnings total.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ChatSessionTest`
Expected: FAIL — compilation error, 3-arg `ChatSession` constructor not defined.

- [ ] **Step 3: Replace `ChatSession.java` content entirely**

```java
package com.mrsmith.chat;

import com.mrsmith.config.AppConfig;
import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderException;
import com.mrsmith.provider.ProviderResponse;
import com.mrsmith.provider.Role;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ChatSession {

    private final Provider provider;
    private final IO io;
    private final AppConfig config;
    private final List<ChatMessage> history = new ArrayList<>();
    private final UsageTracker tracker = new UsageTracker();
    private boolean warned85;
    private boolean warned100;

    public ChatSession(Provider provider, IO io, AppConfig config) {
        this.provider = provider;
        this.io = io;
        this.config = config;
    }

    public void run() throws IOException {
        io.writeLine("Mr Smith. Type /help for commands, /exit to quit.");
        String line;
        while ((line = io.readLine()) != null) {
            if (line.equals("/exit")) {
                break;
            }
            if (handleCommand(line)) {
                continue;
            }
            history.add(new ChatMessage(Role.USER, line));
            try {
                ProviderResponse response = provider.send(history, io::write);
                history.add(response.message());
                io.writeLine("");
                tracker.recordTurn(response.usage(), response.usageEstimated());
                io.writeLine(tracker.lastTurnLine());
                warnIfNearLimit();
            } catch (ProviderException e) {
                if (e.hasPartialContent()) {
                    history.add(new ChatMessage(Role.ASSISTANT, e.partialContent()));
                }
                io.writeLine("");
                io.writeLine("Error: " + e.getMessage());
            } catch (RuntimeException e) {
                io.writeLine("");
                io.writeLine("Error: " + e.getMessage());
            }
        }
    }

    private void warnIfNearLimit() {
        Integer maxContext = config.maxContextTokens();
        if (maxContext == null || maxContext <= 0) {
            return;
        }
        int pct = (int) Math.round(tracker.totalTokens() * 100.0 / maxContext);
        if (pct >= 100) {
            if (!warned100) {
                warned100 = true;
                io.writeLine(String.format(Locale.US,
                        "Warning: session reached 100%% of your configured %,d-token context limit — consider /reset",
                        maxContext));
            }
        } else if (pct >= 85) {
            if (!warned85) {
                warned85 = true;
                io.writeLine(String.format(Locale.US,
                        "Warning: session at %d%% of your configured %,d-token context limit — consider /reset",
                        pct, maxContext));
            }
        }
    }

    private boolean handleCommand(String line) {
        if (!line.startsWith("/")) {
            return false;
        }
        switch (line) {
            case "/reset" -> {
                history.clear();
                tracker.reset();
                warned85 = false;
                warned100 = false;
                io.writeLine("History cleared.");
            }
            case "/usage" -> io.writeLine(usageReport());
            case "/help" -> io.writeLine("Commands: /exit, /reset, /help, /usage. Anything else is sent to the LLM.");
            default -> io.writeLine("Unknown command: " + line + " (type /help)");
        }
        return true;
    }

    private String usageReport() {
        StringBuilder report = new StringBuilder(tracker.usageReport());
        Integer maxContext = config.maxContextTokens();
        if (maxContext != null && maxContext > 0) {
            int pct = (int) Math.round(tracker.totalTokens() * 100.0 / maxContext);
            report.append(String.format(Locale.US, "%n  context limit: %,d configured (%d%% used)",
                    maxContext, pct));
        }
        report.append(String.format(Locale.US, "%n  history: %d messages", history.size()));
        return report.toString();
    }
}
```

- [ ] **Step 4: Update `ChatCommand.java` constructor call**

Replace `ChatSession session = new ChatSession(provider, io);` with:

```java
        ChatSession session = new ChatSession(provider, io, config);
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=ChatSessionTest`
Expected: PASS — 14 tests. The usage-report assertions use exact strings produced by `UsageTracker.usageReport()` (`%,d` grouping, values right-aligned after a 15-column label); if any mismatch, compare against the actual output and correct the expected literal.

- [ ] **Step 6: Run the full suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` — all tests green.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ChatSession.java src/main/java/com/mrsmith/cli/ChatCommand.java src/test/java/com/mrsmith/chat/ChatSessionTest.java
git commit -m "feat: per-turn usage line, /usage command, and near-limit warnings"
```

---

### Task 9: Final Verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` — all tests green (verify the count; the earlier baseline was 35 and this feature adds tests).

- [ ] **Step 2: Package the runnable jar**

Run: `mvn -q package`
Expected: `BUILD SUCCESS`; `target/mr-smith.jar` exists.

- [ ] **Step 3: Smoke-test `--help`**

Run: `java -jar target/mr-smith.jar --help`
Expected: usage shows the new `--max-context` and `--include-usage` options; exit code 0.

- [ ] **Step 4: Manual smoke test (user)**

With your real config at `~/.config/mrsmith/config.json`:
1. Start a chat: `java -jar target/mr-smith.jar`
2. Send a message and confirm a per-turn line like `tokens: N in · M out · total T · session T` appears after the reply.
3. Type `/usage` and confirm the report (and the context-limit line if you set `maxContextTokens`).
4. If your provider does NOT return real usage, confirm the `(est.)` suffix appears. If your provider rejects the request with a 400, add `"includeUsage": false` to the config and retry.
5. Type `/reset` and confirm the totals reset.
