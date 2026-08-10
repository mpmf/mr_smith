# Design: Architecture Polish — Registry Drift and Minor Cleanup

Date: 2026-08-10

## Context

A code review identified one structural drift and several small quality gaps
across the codebase:

- `SubAgentRunner` builds its tool registry directly via
  `ToolRegistry.with(cfg.tools(), skills, io, null)` in
  `ChatSession.applyAgent`, bypassing the `ToolRegistryFactory` seam that the
  main session uses. The two construction paths can drift.
- `EditTool` and `WriteFileTool` write files in place, so a crash mid-write can
  corrupt a file.
- Sixteen classes each declare a private `static final ObjectMapper JSON =
  new ObjectMapper()` — the same default-configured instance repeated.
- `ToolLoop.Accumulator` and `UsageTracker` duplicate the same
  prompt/completion/estimated accumulation math.
- `SubAgentRunner`'s constructor takes 8 positional dependencies.
- Warning messages are scattered `System.err.println("Warning: ...")` calls.

## Goal

Land a set of small, low-risk cleanups that remove the registry drift and
collapse the duplicated boilerplate, without changing observable behavior.

## Scope

- Route the sub-agent registry construction through `ToolRegistryFactory`.
- Atomic writes (temp file + move) for `edit` and `write_file`.
- A single shared `ObjectMapper` instance for all main sources.
- One usage-accounting component shared by `ToolLoop` and `UsageTracker`.
- Bundle `SubAgentRunner`'s dependencies into a context record.
- A `Warn` utility centralizing the warning prints.

## Non-Goals

- No change to the `Tool` contract or `ToolResult` (the structured-payload
  `QuestionTool` change is deferred).
- No dependency injection framework; shared statics are used where a singleton
  value suffices.
- No change to CLI error output in `ChatCommand` (those are startup errors, not
  warnings).
- No change to test-local `ObjectMapper` declarations.

## Architecture

### New `com.mrsmith.util` package

Two small static utilities:

| Type | Responsibility |
|---|---|
| `Json` | `public static final ObjectMapper MAPPER = new ObjectMapper()` — the one shared instance |
| `Warn` | `public static void warn(String message)` → `System.err.println("Warning: " + message)` |

The README architecture table gains a `util` row.

### A. Registry drift

`SubAgentRunner` no longer receives a `Function<AppConfig, ToolRegistry>`
`toolsBuilder`. It receives a `ToolRegistryFactory` and a `SkillCatalog`, and in
`run(...)` builds its registry with
`toolRegistryFactory.create(config, skills, io, null)`. The `taskRunner`
argument already encodes the scope: `null` means "no `task` tool", which is
exactly the sub-agent behavior today. `ChatSession.applyAgent` passes the same
`toolRegistryFactory` it uses for the main session, so both paths construct
registries through one seam.

### B. Atomic file writes

New `com.mrsmith.tool.AtomicFiles` with a static `write(Path target,
byte[] content)`:

1. `Files.createTempFile(parent, ".mrsmith-", ".tmp")` in the target's parent
   directory (same filesystem).
2. Write the bytes to the temp file.
3. If the target exists and the filesystem supports POSIX permissions, copy the
   target's permissions onto the temp file (best-effort; guarded).
4. `Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)`; if
   `AtomicMoveNotSupportedException` is thrown, fall back to
   `Files.move(temp, target, REPLACE_EXISTING)`.
5. On any failure, delete the temp file (best-effort) before propagating.

`EditTool.execute` writes via `AtomicFiles.write(real, updatedBytes)`.
`WriteFileTool.execute` writes via `AtomicFiles.write(target, contentBytes)`.

### C. Shared ObjectMapper

All 16 main-source `private static final ObjectMapper JSON = new
ObjectMapper();` declarations change to
`private static final ObjectMapper JSON = Json.MAPPER;`. Every site keeps its
local `JSON` alias (readable call sites) but points at the single shared
instance. Jackson `ObjectMapper` is thread-safe once configured; all of these
use default configuration, so sharing is safe.

### D. Usage accounting

New `com.mrsmith.chat.UsageAccumulator`:

```java
public final class UsageAccumulator {
    void add(Usage usage, boolean estimated)
    int promptTokens()
    int completionTokens()
    int totalTokens()
    boolean estimated()
    Usage snapshot()   // new Usage(prompt, completion)
}
```

`add` mirrors today's math: null usage ignored; estimated ORs in; null token
fields skipped.

- `ToolLoop` deletes its private `Accumulator` and uses `UsageAccumulator`
  instead; `LoopResult` is built from `acc.snapshot()` and `acc.estimated()`.
- `UsageTracker` holds a private `UsageAccumulator` and delegates its
  accumulation and total/prompt/completion accessors to it; it keeps its
  `lastTurn`/`lastTurnEstimated` and formatting logic.

### E. SubAgentRunner constructor

New `SubAgentRunner.Context` record bundling the dependencies:

```java
public record Context(AgentCatalog agents, ProviderFactory providerFactory,
                      ToolRegistryFactory toolRegistryFactory, SkillCatalog skills,
                      IO io, UsageTracker tracker,
                      Supplier<AppConfig> currentConfig,
                      Supplier<UUID> sessionId, Supplier<ToolBudget> budget) {
}
```

`SubAgentRunner(Context context)` stores them. Test construction sites and
`ChatSession.applyAgent` are updated.

### F. Centralize warnings

The ten warning sites switch to `Warn.warn(...)`, preserving the exact output
text (`Warning: <message>`):

- `ChatSession` — session-folder and transcript-write failures (6 sites)
- `SubAgentRunner` — sub-agent transcript-write failures (2 sites)
- `SkillCatalog` — malformed skill skipping
- `SseParser` — malformed SSE chunk skipping

## Error Handling

| Scenario | Behavior |
|---|---|
| Atomic write fails mid-write | temp file removed (best-effort), original untouched, exception propagates as today |
| Filesystem lacks `ATOMIC_MOVE` | fall back to a plain replace move |
| POSIX permissions unsupported | skip the permission copy; write still succeeds |
| Warnings | unchanged text via `Warn.warn`; no behavior change |

## Testing

- `UsageAccumulatorTest` (new) — add/accumulate, null handling, estimated flag,
  snapshot.
- `UsageTrackerTest` — must pass unchanged (behavior preserved through the
  delegate).
- `AtomicFiles`/file-tool tests — existing `EditToolTest`/`FileToolsTest` keep
  passing; add a permission-preservation check (POSIX only, skipped otherwise)
  and an overwrite-atomicity smoke test.
- `SubAgentRunnerTest` — update constructor call sites; existing tests pass
  unchanged.
- `ChatSessionTest` — `applyAgent` wiring change; existing tests pass.
- Full suite stays green (299 tests; + a handful of new ones).
