# Mr Smith — Basic Agentic Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an interactive multi-turn chat CLI (Java 21, Maven) that streams answers from any OpenAI-compatible endpoint, with clean seams for future web/Telegram/REST/agent extensions.

**Architecture:** Single Maven module with disciplined package boundaries under `com.mrsmith`. Two ports — `Provider` (send conversation → streamed tokens) and `IO` (read/write) — implemented by `OpenAiCompatibleProvider` and `ReplIo`. A `ChatSession` orchestrates the conversation loop and owns message history. Config loads from a JSON file, env vars, and CLI flags in that precedence order. No framework; only picocli, Jackson, and JDK `HttpClient`.

**Tech Stack:** Java 21 (compiled with `maven.compiler.release=21`, toolchain verified on JDK 26) · Maven · picocli 4.7.6 · Jackson 2.17.2 · JUnit 5 · OkHttp MockWebServer (tests only)

**Spec:** `docs/superpowers/specs/2026-08-01-agentic-harness-design.md`

**Design notes / deviations from spec wording:**
- The spec's `Sink<String>` token sink is implemented with the JDK `java.util.function.Consumer<String>` — same contract, no custom type needed.
- Config file format is JSON (`~/.config/mrsmith/config.json`) parsed by Jackson, not TOML — Jackson cannot parse TOML and adding a TOML parser contradicts the minimal-dependency goal (this was corrected in the spec already).
- The provider retry delay (2s) is injectable via a package-private constructor so tests don't sleep.

## File Structure

All paths relative to repo root `/Users/marcoferreira/Projects/mr_smith`.

**Production sources** (`src/main/java/com/mrsmith/`):

| File | Responsibility |
|---|---|
| `Main.java` | Entry point; runs picocli and exits with its code |
| `cli/ChatCommand.java` | picocli command; defines `--model`/`--base-url`/`--system-prompt`/`--api-key`; wires config→provider→io→session |
| `config/AppConfig.java` | Record holding `apiKey`, `baseUrl`, `model`, `systemPrompt`; normalizes base URL |
| `config/ConfigException.java` | RuntimeException for fatal config problems (e.g. missing key) |
| `config/ConfigLoader.java` | Merges config file + env + CLI per precedence; fails fast if key missing |
| `io/IO.java` | Port: `readLine`, `write`, `writeLine` |
| `io/ReplIo.java` | Stdio implementation (writes flush immediately so tokens stream) |
| `provider/Role.java` | Enum: `SYSTEM`, `USER`, `ASSISTANT` with lowercase API names |
| `provider/ChatMessage.java` | Record: `role` + `content` |
| `provider/Provider.java` | Port: `send(List<ChatMessage>, Consumer<String>)` → `ChatMessage` |
| `provider/ProviderException.java` | RuntimeException carrying optional partial content from interrupted streams |
| `provider/SseParser.java` | Parses SSE body into deltas, streams them, returns assembled text |
| `provider/OpenAiCompatibleProvider.java` | HTTP client for `/chat/completions`; stream=true; 4xx→error, 5xx→retry once |

**Test sources** (`src/test/java/com/mrsmith/`):

| File | Responsibility |
|---|---|
| `config/ConfigLoaderTest.java` | Precedence, missing key, malformed file, base URL normalization |
| `io/ReplIoTest.java` | write/writeLine/readLine via in-memory reader/writer |
| `provider/RoleTest.java` | API names |
| `provider/SseParserTest.java` | Delta extraction, `[DONE]`, malformed lines, truncated stream |
| `provider/OpenAiCompatibleProviderTest.java` | MockWebServer streaming, request body, 4xx, 5xx retry |
| `chat/ChatSessionTest.java` | History, `/reset`, unknown command, provider errors, partial content |
| `cli/ChatCommandTest.java` | `--help` exits 0 (smoke) |

Build files: `pom.xml` (deps + shade for runnable jar), `.gitignore`.

## Build & Test Commands

- Compile: `mvn -q compile`
- Test: `mvn -q test`
- Package (runnable jar): `mvn -q package` → `target/mr-smith.jar`
- Run: `java -jar target/mr-smith.jar [--model X] [--base-url Y] [--system-prompt Z] [--api-key K]`

Expected baseline after scaffold: `mvn -q test` prints `BUILD SUCCESS`.

---

### Task 1: Project Scaffolding

**Files:**
- Create: `pom.xml`
- Create: `.gitignore`

- [ ] **Step 1: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.mrsmith</groupId>
  <artifactId>mr-smith</artifactId>
  <version>0.1.0</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <jackson.version>2.17.2</jackson.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>info.picocli</groupId>
      <artifactId>picocli</artifactId>
      <version>4.7.6</version>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>${jackson.version}</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.10.2</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.squareup.okhttp3</groupId>
      <artifactId>mockwebserver</artifactId>
      <version>4.12.0</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.2.5</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.5.2</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <finalName>mr-smith</finalName>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>com.mrsmith.Main</mainClass>
                </transformer>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Create `.gitignore`**

```
target/
.idea/
*.iml
.DS_Store
*.class
```

- [ ] **Step 3: Verify the build runs clean with no sources**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` (no sources, no tests yet).

- [ ] **Step 4: Commit**

```bash
git add pom.xml .gitignore
git commit -m "chore: scaffold Maven project with picocli, jackson, junit, mockwebserver"
```

---

### Task 2: Role and ChatMessage DTOs

**Files:**
- Create: `src/main/java/com/mrsmith/provider/Role.java`
- Create: `src/main/java/com/mrsmith/provider/ChatMessage.java`
- Create: `src/test/java/com/mrsmith/provider/RoleTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.mrsmith.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoleTest {

    @Test
    void apiNamesAreLowercaseWireFormat() {
        assertEquals("system", Role.SYSTEM.apiName());
        assertEquals("user", Role.USER.apiName());
        assertEquals("assistant", Role.ASSISTANT.apiName());
    }

    @Test
    void chatMessageExposesWireFormatRoleName() {
        ChatMessage message = new ChatMessage(Role.USER, "hello");
        assertEquals("user", message.roleName());
        assertEquals("hello", message.content());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=RoleTest`
Expected: FAIL — compilation error, `Role` / `ChatMessage` not defined.

- [ ] **Step 3: Implement `Role.java`**

```java
package com.mrsmith.provider;

public enum Role {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant");

    private final String apiName;

    Role(String apiName) {
        this.apiName = apiName;
    }

    public String apiName() {
        return apiName;
    }
}
```

- [ ] **Step 4: Implement `ChatMessage.java`**

```java
package com.mrsmith.provider;

public record ChatMessage(Role role, String content) {

    public String roleName() {
        return role.apiName();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=RoleTest`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/provider/Role.java src/main/java/com/mrsmith/provider/ChatMessage.java src/test/java/com/mrsmith/provider/RoleTest.java
git commit -m "feat: add Role and ChatMessage DTOs"
```

---

### Task 3: IO Port and ReplIo Adapter

**Files:**
- Create: `src/main/java/com/mrsmith/io/IO.java`
- Create: `src/main/java/com/mrsmith/io/ReplIo.java`
- Create: `src/test/java/com/mrsmith/io/ReplIoTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.mrsmith.io;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplIoTest {

    @Test
    void readsLinesFromReader() throws IOException {
        ReplIo io = new ReplIo(new BufferedReader(new StringReader("hello\n/exit\n")), new PrintStream(new ByteArrayOutputStream()));
        assertEquals("hello", io.readLine());
        assertEquals("/exit", io.readLine());
    }

    @Test
    void writeAppendsWithoutNewline() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ReplIo io = new ReplIo(new BufferedReader(new StringReader("")), new PrintStream(buffer));
        io.write("a");
        io.write("b");
        assertEquals("ab", buffer.toString());
    }

    @Test
    void writeLineAppendsNewline() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ReplIo io = new ReplIo(new BufferedReader(new StringReader("")), new PrintStream(buffer));
        io.writeLine("hi");
        assertEquals("hi\n", buffer.toString());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ReplIoTest`
Expected: FAIL — compilation error, `IO` / `ReplIo` not defined.

- [ ] **Step 3: Implement `IO.java`**

```java
package com.mrsmith.io;

import java.io.IOException;

public interface IO {

    String readLine() throws IOException;

    void write(String text);

    void writeLine(String line);
}
```

- [ ] **Step 4: Implement `ReplIo.java`**

```java
package com.mrsmith.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;

public class ReplIo implements IO {

    private final BufferedReader reader;
    private final PrintStream out;

    public ReplIo() {
        this(new BufferedReader(new InputStreamReader(System.in)), System.out);
    }

    ReplIo(BufferedReader reader, PrintStream out) {
        this.reader = reader;
        this.out = out;
    }

    @Override
    public String readLine() throws IOException {
        return reader.readLine();
    }

    @Override
    public void write(String text) {
        out.print(text);
        out.flush();
    }

    @Override
    public void writeLine(String line) {
        out.println(line);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=ReplIoTest`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/io/IO.java src/main/java/com/mrsmith/io/ReplIo.java src/test/java/com/mrsmith/io/ReplIoTest.java
git commit -m "feat: add IO port and ReplIo adapter"
```

---

### Task 4: AppConfig, ConfigException, ConfigLoader

**Files:**
- Create: `src/main/java/com/mrsmith/config/AppConfig.java`
- Create: `src/main/java/com/mrsmith/config/ConfigException.java`
- Create: `src/main/java/com/mrsmith/config/ConfigLoader.java`
- Create: `src/test/java/com/mrsmith/config/ConfigLoaderTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.mrsmith.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void missingApiKeyFailsFast() {
        assertThrows(ConfigException.class,
                () -> ConfigLoader.load(noFile(), null, null, null, null, Map.of()));
    }

    @Test
    void defaultsWhenNoFileEnvOrCli() {
        AppConfig config = ConfigLoader.load(noFile(), null, null, null, null,
                Map.of("OPENAI_API_KEY", "sk-x"));
        assertEquals("gpt-4o-mini", config.model());
        assertEquals("https://api.openai.com/v1", config.baseUrl());
        assertNull(config.systemPrompt());
    }

    @Test
    void envApiKeyIsAccepted() {
        AppConfig config = ConfigLoader.load(noFile(), null, null, null, null,
                Map.of("OPENAI_API_KEY", "sk-env"));
        assertEquals("sk-env", config.apiKey());
    }

    @Test
    void envOverridesFile() throws IOException {
        Path file = writeConfig("{ \"model\": \"from-file\", \"baseUrl\": \"https://file.example\", \"systemPrompt\": \"file prompt\" }");
        AppConfig config = ConfigLoader.load(file, null, null, null, null,
                Map.of("OPENAI_API_KEY", "sk-env", "MRSMITH_MODEL", "from-env"));
        assertEquals("from-env", config.model());
        assertEquals("https://file.example", config.baseUrl());
        assertEquals("file prompt", config.systemPrompt());
    }

    @Test
    void cliOverridesEnv() throws IOException {
        Path file = writeConfig("{ \"model\": \"from-file\" }");
        AppConfig config = ConfigLoader.load(file, "from-cli", null, null, "sk-cli",
                Map.of("OPENAI_API_KEY", "sk-env", "MRSMITH_MODEL", "from-env"));
        assertEquals("from-cli", config.model());
        assertEquals("sk-cli", config.apiKey());
    }

    @Test
    void malformedFileFallsBackToDefaults() throws IOException {
        Path file = writeConfig("not valid json {{{");
        AppConfig config = ConfigLoader.load(file, null, null, null, "sk-x", Map.of());
        assertEquals("gpt-4o-mini", config.model());
        assertEquals("sk-x", config.apiKey());
    }

    @Test
    void baseUrlTrailingSlashIsStripped() throws IOException {
        Path file = writeConfig("{ \"baseUrl\": \"https://example.com/v1/\" }");
        AppConfig config = ConfigLoader.load(file, null, null, null, "sk-x", Map.of());
        assertEquals("https://example.com/v1", config.baseUrl());
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
Expected: FAIL — compilation error, config classes not defined.

- [ ] **Step 3: Implement `AppConfig.java`**

```java
package com.mrsmith.config;

import java.util.Objects;

public record AppConfig(String apiKey, String baseUrl, String model, String systemPrompt) {

    public AppConfig {
        Objects.requireNonNull(apiKey, "apiKey is required");
        Objects.requireNonNull(baseUrl, "baseUrl is required");
        Objects.requireNonNull(model, "model is required");
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }
}
```

- [ ] **Step 4: Implement `ConfigException.java`**

```java
package com.mrsmith.config;

public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: Implement `ConfigLoader.java`**

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

    public static AppConfig load(String cliModel, String cliBaseUrl, String cliSystemPrompt, String cliApiKey) {
        return load(DEFAULT_CONFIG_PATH, cliModel, cliBaseUrl, cliSystemPrompt, cliApiKey, System.getenv());
    }

    public static AppConfig load(Path configFile, String cliModel, String cliBaseUrl,
                                 String cliSystemPrompt, String cliApiKey, Map<String, String> env) {
        String fileModel = null;
        String fileBaseUrl = null;
        String fileSystemPrompt = null;

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
            } catch (IOException e) {
                System.err.println("Warning: could not read config file " + configFile
                        + " (" + e.getMessage() + "). Falling back to env vars and defaults.");
            }
        }

        String model = firstNonNull(cliModel, env.get("MRSMITH_MODEL"), fileModel, "gpt-4o-mini");
        String baseUrl = firstNonNull(cliBaseUrl, env.get("MRSMITH_BASE_URL"), fileBaseUrl,
                "https://api.openai.com/v1");
        String systemPrompt = firstNonNull(cliSystemPrompt, fileSystemPrompt);
        String apiKey = firstNonNull(cliApiKey, env.get("OPENAI_API_KEY"));

        if (apiKey == null) {
            throw new ConfigException(
                    "OPENAI_API_KEY is not set. Export it as an environment variable "
                            + "(e.g. export OPENAI_API_KEY=sk-...) or pass it with --api-key.");
        }

        return new AppConfig(apiKey, baseUrl, model, systemPrompt);
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -q test -Dtest=ConfigLoaderTest`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mrsmith/config/ src/test/java/com/mrsmith/config/ConfigLoaderTest.java
git commit -m "feat: add config loading with file, env, and CLI precedence"
```

---

### Task 5: Provider Port and ProviderException

**Files:**
- Create: `src/main/java/com/mrsmith/provider/Provider.java`
- Create: `src/main/java/com/mrsmith/provider/ProviderException.java`
- Create: `src/test/java/com/mrsmith/provider/ProviderExceptionTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.mrsmith.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderExceptionTest {

    @Test
    void exposesOptionalPartialContent() {
        ProviderException withPartial = new ProviderException("Stream interrupted", null, "half a reply");
        assertTrue(withPartial.hasPartialContent());
        assertEquals("half a reply", withPartial.partialContent());

        ProviderException plain = new ProviderException("HTTP 401: bad key");
        assertFalse(plain.hasPartialContent());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ProviderExceptionTest`
Expected: FAIL — compilation error, `ProviderException` not defined.

- [ ] **Step 3: Implement `Provider.java`**

```java
package com.mrsmith.provider;

import java.util.List;
import java.util.function.Consumer;

public interface Provider {

    ChatMessage send(List<ChatMessage> history, Consumer<String> tokenSink);
}
```

- [ ] **Step 4: Implement `ProviderException.java`**

```java
package com.mrsmith.provider;

public class ProviderException extends RuntimeException {

    private final String partialContent;

    public ProviderException(String message) {
        this(message, null, null);
    }

    public ProviderException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public ProviderException(String message, Throwable cause, String partialContent) {
        super(message, cause);
        this.partialContent = partialContent;
    }

    public boolean hasPartialContent() {
        return partialContent != null;
    }

    public String partialContent() {
        return partialContent;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=ProviderExceptionTest`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mrsmith/provider/Provider.java src/main/java/com/mrsmith/provider/ProviderException.java src/test/java/com/mrsmith/provider/ProviderExceptionTest.java
git commit -m "feat: add Provider port and ProviderException with partial content"
```

---

### Task 6: SseParser

**Files:**
- Create: `src/main/java/com/mrsmith/provider/SseParser.java`
- Create: `src/test/java/com/mrsmith/provider/SseParserTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.mrsmith.provider;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SseParserTest {

    @Test
    void extractsDeltasInOrder() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"content":"Hello"}}]}

                data: {"choices":[{"delta":{"content":" world"}}]}

                data: [DONE]

                """;
        String full = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add);
        assertEquals("Hello world", full);
        assertEquals(List.of("Hello", " world"), deltas);
    }

    @Test
    void ignoresChunksWithoutContent() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"role":"assistant"}}]}

                data: {"choices":[{"delta":{"content":"hi"}}]}

                data: [DONE]

                """;
        String full = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add);
        assertEquals("hi", full);
        assertEquals(List.of("hi"), deltas);
    }

    @Test
    void usesPartialTextWhenStreamEndsWithoutDone() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"content":"partial"}}]}
                """;
        String full = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add);
        assertEquals("partial", full);
    }

    @Test
    void skipsMalformedLinesAndContinues() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: not-json

                data: {"choices":[{"delta":{"content":"ok"}}]}

                data: [DONE]

                """;
        String full = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add);
        assertEquals("ok", full);
        assertEquals(List.of("ok"), deltas);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=SseParserTest`
Expected: FAIL — compilation error, `SseParser` not defined.

- [ ] **Step 3: Implement `SseParser.java`**

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

    public static String consume(BufferedReader reader, Consumer<String> deltaSink) throws IOException {
        StringBuilder full = new StringBuilder();
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
            String delta = extractDelta(payload);
            if (delta != null && !delta.isEmpty()) {
                deltaSink.accept(delta);
                full.append(delta);
            }
        }
        return full.toString();
    }

    private static String extractDelta(String payload) {
        try {
            JsonNode node = JSON.readTree(payload);
            JsonNode delta = node.path("choices").path(0).path("delta");
            return delta.isMissingNode() ? null : delta.path("content").asText(null);
        } catch (IOException e) {
            System.err.println("Warning: malformed SSE chunk, skipping: " + payload);
            return null;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=SseParserTest`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/provider/SseParser.java src/test/java/com/mrsmith/provider/SseParserTest.java
git commit -m "feat: add SSE parser for streaming deltas"
```

---

### Task 7: OpenAiCompatibleProvider

**Files:**
- Create: `src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java`
- Create: `src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.mrsmith.provider;

import com.mrsmith.config.AppConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleProviderTest {

    private MockWebServer server;
    private OpenAiCompatibleProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        AppConfig config = new AppConfig("sk-test", server.url("/").toString(), "test-model", null);
        provider = new OpenAiCompatibleProvider(config, HttpClient.newHttpClient(), 0L);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void streamsTokensAndReturnsFullMessage() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"delta":{"content":"Hi"}}]}

                        data: {"choices":[{"delta":{"content":" there"}}]}

                        data: [DONE]

                        """));
        List<String> deltas = new ArrayList<>();
        ChatMessage reply = provider.send(List.of(new ChatMessage(Role.USER, "hello")), deltas::add);
        assertEquals("Hi there", reply.content());
        assertEquals(List.of("Hi", " there"), deltas);
    }

    @Test
    void sendsCorrectRequestBodyAndAuth() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        provider.send(List.of(new ChatMessage(Role.USER, "hello")), s -> { });
        RecordedRequest request = server.takeRequest();
        assertEquals("Bearer sk-test", request.getHeader("Authorization"));
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"model\":\"test-model\""));
        assertTrue(body.contains("\"stream\":true"));
        assertTrue(body.contains("\"role\":\"user\""));
        assertTrue(body.contains("\"content\":\"hello\""));
    }

    @Test
    void includesSystemPromptWhenConfigured() throws Exception {
        AppConfig config = new AppConfig("sk-test", server.url("/").toString(), "test-model", "You are helpful");
        provider = new OpenAiCompatibleProvider(config, HttpClient.newHttpClient(), 0L);
        server.enqueue(new MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"));
        provider.send(List.of(new ChatMessage(Role.USER, "hello")), s -> { });
        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"role\":\"system\""));
        assertTrue(body.contains("\"content\":\"You are helpful\""));
    }

    @Test
    void throwsOn4xx() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"invalid key\"}"));
        ProviderException e = assertThrows(ProviderException.class,
                () -> provider.send(List.of(new ChatMessage(Role.USER, "hi")), s -> { }));
        assertTrue(e.getMessage().contains("401"));
    }

    @Test
    void retriesOnceOn5xxThenSucceeds() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"));
        List<String> deltas = new ArrayList<>();
        ChatMessage reply = provider.send(List.of(new ChatMessage(Role.USER, "hi")), deltas::add);
        assertEquals("ok", reply.content());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void givesUpAfterRetryOnPersistent5xx() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        server.enqueue(new MockResponse().setResponseCode(502).setBody("boom2"));
        ProviderException e = assertThrows(ProviderException.class,
                () -> provider.send(List.of(new ChatMessage(Role.USER, "hi")), s -> { }));
        assertTrue(e.getMessage().contains("502"));
        assertEquals(2, server.getRequestCount());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=OpenAiCompatibleProviderTest`
Expected: FAIL — compilation error, `OpenAiCompatibleProvider` not defined.

- [ ] **Step 3: Implement `OpenAiCompatibleProvider.java`**

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
    public ChatMessage send(List<ChatMessage> history, Consumer<String> tokenSink) {
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

    private ChatMessage doSend(List<ChatMessage> history, Consumer<String> tokenSink)
            throws IOException, InterruptedException {
        HttpRequest request = buildRequest(buildRequestBody(history));
        HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        return handleResponse(response, request, tokenSink);
    }

    private ChatMessage handleResponse(HttpResponse<InputStream> response, HttpRequest request,
                                       Consumer<String> tokenSink) throws IOException, InterruptedException {
        String partial = null;
        try {
            if (response.statusCode() >= 500) {
                response.body().close();
                Thread.sleep(retryDelayMillis);
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 500) {
                    throw new ProviderException("Provider error HTTP " + response.statusCode()
                            + " after retry: " + readErrorBody(response));
                }
            }
            if (response.statusCode() >= 400) {
                throw new ProviderException("Provider error HTTP " + response.statusCode()
                        + ": " + readErrorBody(response));
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()));
            partial = SseParser.consume(reader, tokenSink);
            return new ChatMessage(Role.ASSISTANT, partial);
        } catch (ProviderException e) {
            throw e;
        } catch (IOException e) {
            String message = partial == null
                    ? "Network error during request: " + e.getMessage()
                    : "Stream interrupted: " + e.getMessage();
            throw new ProviderException(message, e, partial);
        }
    }

    private String buildRequestBody(List<ChatMessage> history) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);
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

    private static String readErrorBody(HttpResponse<InputStream> response) throws IOException {
        try (InputStream body = response.body()) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=OpenAiCompatibleProviderTest`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/provider/OpenAiCompatibleProvider.java src/test/java/com/mrsmith/provider/OpenAiCompatibleProviderTest.java
git commit -m "feat: add OpenAI-compatible provider with streaming and retry"
```

---

### Task 8: ChatSession

**Files:**
- Create: `src/main/java/com/mrsmith/chat/ChatSession.java`
- Create: `src/test/java/com/mrsmith/chat/ChatSessionTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.mrsmith.chat;

import com.mrsmith.config.AppConfig;
import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderException;
import com.mrsmith.provider.Role;
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
        assertEquals(2, ok.calls);
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

    private AppConfig config() {
        return new AppConfig("sk-test", "https://example.com/v1", "test-model", null);
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
        final List<List<ChatMessage>> receivedHistories = new ArrayList<>();
        int calls = 0;

        @Override
        public ChatMessage send(List<ChatMessage> history, Consumer<String> tokenSink) {
            receivedHistories.add(new ArrayList<>(history));
            calls++;
            ChatMessage last = history.get(history.size() - 1);
            String reply = last.content() + " response";
            tokenSink.accept(reply);
            return new ChatMessage(Role.ASSISTANT, reply);
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
        public ChatMessage send(List<ChatMessage> history, Consumer<String> tokenSink) {
            if (calls++ == 0) {
                return first.send(history, tokenSink);
            }
            return then.send(history, tokenSink);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ChatSessionTest`
Expected: FAIL — compilation error, `ChatSession` not defined.

- [ ] **Step 3: Implement `ChatSession.java`**

```java
package com.mrsmith.chat;

import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderException;
import com.mrsmith.provider.Role;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ChatSession {

    private final Provider provider;
    private final IO io;
    private final List<ChatMessage> history = new ArrayList<>();

    public ChatSession(Provider provider, IO io) {
        this.provider = provider;
        this.io = io;
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
                ChatMessage reply = provider.send(history, io::write);
                history.add(reply);
                io.writeLine();
            } catch (ProviderException e) {
                if (e.hasPartialContent()) {
                    history.add(new ChatMessage(Role.ASSISTANT, e.partialContent()));
                }
                io.writeLine();
                io.writeLine("Error: " + e.getMessage());
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
                io.writeLine("History cleared.");
            }
            case "/help" -> io.writeLine("Commands: /exit, /reset, /help. Anything else is sent to the LLM.");
            default -> io.writeLine("Unknown command: " + line + " (type /help)");
        }
        return true;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=ChatSessionTest`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mrsmith/chat/ChatSession.java src/test/java/com/mrsmith/chat/ChatSessionTest.java
git commit -m "feat: add ChatSession REPL loop with history and error recovery"
```

---

### Task 9: Main Entry Point and ChatCommand Wiring

**Files:**
- Create: `src/main/java/com/mrsmith/Main.java`
- Create: `src/main/java/com/mrsmith/cli/ChatCommand.java`
- Create: `src/test/java/com/mrsmith/cli/ChatCommandTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.mrsmith.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCommandTest {

    @Test
    void helpExitsZeroAndPrintsUsage() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = new CommandLine(new ChatCommand()).execute("--help");
            assertEquals(0, exit);
            assertTrue(out.toString().contains("--model"));
        } finally {
            System.setOut(original);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ChatCommandTest`
Expected: FAIL — compilation error, `ChatCommand` not defined.

- [ ] **Step 3: Implement `Main.java`**

```java
package com.mrsmith;

import com.mrsmith.cli.ChatCommand;
import picocli.CommandLine;

public class Main {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ChatCommand()).execute(args);
        System.exit(exitCode);
    }
}
```

- [ ] **Step 4: Implement `ChatCommand.java`**

```java
package com.mrsmith.cli;

import com.mrsmith.chat.ChatSession;
import com.mrsmith.config.AppConfig;
import com.mrsmith.config.ConfigException;
import com.mrsmith.config.ConfigLoader;
import com.mrsmith.io.IO;
import com.mrsmith.io.ReplIo;
import com.mrsmith.provider.OpenAiCompatibleProvider;
import com.mrsmith.provider.Provider;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.util.concurrent.Callable;

@Command(name = "mrsmith", mixinStandardHelpOptions = true,
        description = "Interactive chat against any OpenAI-compatible endpoint.")
public class ChatCommand implements Callable<Integer> {

    @Option(names = "--model", description = "Model to use (overrides config file and env).")
    private String model;

    @Option(names = "--base-url", description = "Provider base URL, e.g. https://api.openai.com/v1")
    private String baseUrl;

    @Option(names = "--system-prompt", description = "Optional system prompt.")
    private String systemPrompt;

    @Option(names = "--api-key", description = "API key (overrides OPENAI_API_KEY).")
    private String apiKey;

    @Override
    public Integer call() {
        AppConfig config;
        try {
            config = ConfigLoader.load(model, baseUrl, systemPrompt, apiKey);
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
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=ChatCommandTest`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 6: Run the full test suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` with all 6 test classes passing.

- [ ] **Step 7: Package the runnable jar**

Run: `mvn -q package`
Expected: `BUILD SUCCESS`; `target/mr-smith.jar` exists.

- [ ] **Step 8: Smoke-test `--help`**

Run: `java -jar target/mr-smith.jar --help`
Expected: prints usage with `--model`, `--base-url`, `--system-prompt`, `--api-key`, `--help`; exit code 0.

- [ ] **Step 9: Verify missing-key fails fast**

Run: `env -u OPENAI_API_KEY java -jar target/mr-smith.jar`
Expected: prints `OPENAI_API_KEY is not set...` to stderr; exit code 1.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/mrsmith/Main.java src/main/java/com/mrsmith/cli/ChatCommand.java src/test/java/com/mrsmith/cli/ChatCommandTest.java
git commit -m "feat: wire Main and ChatCommand CLI"
```

---

### Task 10: Manual Smoke Test Against a Real Provider

**Files:** none (manual verification)

- [ ] **Step 1: Run the CLI with a real key**

```bash
export OPENAI_API_KEY=sk-your-key
java -jar target/mr-smith.jar
```

Expected: banner appears; typing `hello` streams a token-by-token reply; assistant text is added to context; `/reset` clears; `/help` prints commands; `/exit` quits.

- [ ] **Step 2: Verify streaming is live**

While an answer streams, confirm tokens appear incrementally rather than all at once.

- [ ] **Step 3: Verify multi-turn context**

Ask "what color is the sky?" then "what was my first question?" — the second answer should reference the first.

- [ ] **Step 4: Verify a different provider**

If you have an alternate OpenAI-compatible endpoint (e.g. Ollama on `http://localhost:11434/v1`, or Groq):

```bash
java -jar target/mr-smith.jar --base-url http://localhost:11434/v1 --model llama3.2
```

Expected: chat works against that provider.

- [ ] **Step 5: Verify config file support**

Create `~/.config/mrsmith/config.json` with `{"model": "...", "baseUrl": "...", "systemPrompt": "..."}`, run without flags, confirm the file values apply (and that deleting the file returns to defaults).
