# Design: Shell Command Classification & Per-Command Approval

Date: 2026-08-10

## Problem

The `shell` tool runs arbitrary `bash -c` commands, and today *every* shell
command requires the same confirmation prompt (`Run shell(<command>) [y/N]?`)
because `ShellTool.isReadOnly()` is always `false`. Read-only commands like
`ls` or `git status` are just as gated as destructive ones like `rm -rf`. The
approval burden is identical regardless of how risky the command is, and the
`a`/`always` option covers the whole `shell` tool at once — approving one
dangerous command silently approves every later shell command too.

## Goal

Classify shell commands into three categories — **harmless** (read-only), and
two that require permission (**dangerous** and **unknown**) — and only prompt
when a command is not clearly harmless. The always-allow option becomes
per-command (keyed on the command identity, e.g. `git commit`, not the whole
shell tool), so approving one command does not unlock every other one.

## Scope

- A new pure classifier, `ShellCommandClassifier`, that parses a command
  string and returns a verdict (SAFE / DANGEROUS / UNKNOWN) plus always-allow
  key(s).
- The `Tool` interface gains a single default method, `approvalCheck(args)`,
  which subsumes the `isReadOnly()` gate in `ToolLoop`.
- `ShellTool` overrides `approvalCheck` to delegate to the classifier.
- Always-allow becomes per-command for `shell`: approving a command records
  the canonical identity of each dangerous/unknown segment in the chain.
- Hardcoded default classification lists, extendable per-agent via
  `config.json`.

## Non-Goals

- No change to the `web_fetch` private-host approval prompt (per-host, scoped
  to a single fetch chain). It stays `[y/N]`.
- No "never/always-deny" option.
- No persistence across processes — always-allow lasts only until the session
  is reset or the process exits.
- The classifier is a convenience heuristic, **not a security boundary**. The
  existing CWD path containment still applies, and the user can always decline.
- No change to read-only flags on the other built-in tools; their behavior is
  unchanged (read-only tools run automatically, others prompt).

## Architecture

### `ShellCommandClassifier` (new, `com.mrsmith.tool`)

A pure, stateless class:

```java
public final class ShellCommandClassifier {

    enum Verdict { SAFE, DANGEROUS, UNKNOWN }

    record Classification(Verdict verdict, List<String> keys) {
        static Classification safe() { return new Classification(Verdict.SAFE, List.of()); }
        boolean requiresApproval() { return verdict != Verdict.SAFE; }
    }

    public ShellCommandClassifier() { ... }                       // defaults
    public ShellCommandClassifier(ShellConfig config) { ... }     // defaults + config

    public Classification classify(String command) { ... }
}
```

`classify` works in one quote-aware pass over the command string:

1. **Redirection guard** — any unquoted `>` (covering `>`, `>>`, `2>`, `&>`)
   marks the command DANGEROUS even if every binary is safe (`cat f > out`).
2. **Segment split** — the command is split into segments on unquoted control
   operators `;`, `&&`, `||`, `|`, `&`, and newline. Every segment is
   classified; a single non-SAFE segment makes the whole command require
   approval.
3. **Per-segment verdict** — for each segment's leading binary, in precedence
   order:
   - in the **dangerous** lists — the config `shellDangerousCommands` and the
     built-in dangerous set (`rm`, `rmdir`, `mv`, `cp`, `touch`, `mkdir`,
     `chmod`, `chown`, `ln`, `dd`, `tee`, `sed`, `truncate`, `install`,
     `patch`, `shred`, `unlink`, `mount`, `umount`) → DANGEROUS. A two-token
     config-dangerous entry applies only to that binary+subcommand (e.g.
     `mydeploy --push`); other subcommands of that binary are UNKNOWN.
   - a **subcommand-aware** binary (built-in `git`, or any binary with at
     least one harmless subcommand entry from config) → SAFE only if the second
     word is a known harmless subcommand (`git status`, `git diff`, `git log`,
     `git show`, `git branch`, `git ls-files`, `git rev-parse`, `git remote`,
     `git tag`); any other subcommand (or a bare binary with no subcommand) →
     DANGEROUS.
   - a **flag-aware** binary — `find` with `-delete`, `-exec`, `-execdir`,
      `-ok`, or `-okdir`, and `sort` with `-o` (including attached short forms
      like `-oout`) or `--output[=...]` → DANGEROUS; otherwise `find` and
      `sort` stay SAFE.
   - a **subcommand-mutation** rule for `git` — the safe subcommands `branch`,
      `tag`, and `remote` are DANGEROUS when they carry mutation words:
      `branch` with `-d`/`-D`/`--delete`, `tag` with `-d`/`--delete`, `remote`
      with `remove`/`rm`. Plain listing forms (`git branch`, `git tag`,
      `git remote -v`) stay SAFE.
   - in the **safe** lists — the config `shellHarmlessCommands` and the
     built-in safe set (`ls`, `cat`, `pwd`, `echo`, `printf`, `head`, `tail`,
     `wc`, `grep`, `find`, `diff`, `sort`, `uniq`, `cut`, `tr`, `file`,
     `stat`, `du`, `df`, `which`, `readlink`, `basename`, `dirname`, `date`,
     `cal`, `whoami`, `uname`, `hostname`, `env`, `printenv`, `id`, `tree`,
     plus shell builtins `cd`, `export`, `set`, `unset`) → SAFE.
   - anything else → UNKNOWN.

   A harmless entry cannot override a dangerous one: the dangerous lists are
   always evaluated first.
4. **Aggregation** — the overall verdict is DANGEROUS if any segment is
   DANGEROUS (or redirection was seen), else UNKNOWN if any segment is UNKNOWN,
   else SAFE. DANGEROUS and UNKNOWN both require approval; the distinction is
   used only for the prompt reason label.

**Always-allow key(s):**

- **No redirection** — one key per non-SAFE segment: the binary name, plus the
  subcommand when the binary is subcommand-aware, plus the dangerous flag or
  git mutation word when one applies (e.g. `find -delete`, `git branch -d`,
  `sort -o`). `git commit -m "x"` → key `git commit`; `rm -rf target` → key
  `rm`; `mvn -q clean install` → key `mvn`; the chain
  `git add app.js && git commit -m wip` → keys `git add` and `git commit`.
- **Redirection present** — a single key: the whole command normalized (all
  arguments stripped, redirection operators preserved, separators preserved).
  `cat f > out` → key `cat >`.

### `Tool` interface change

Add a default method and a nested record:

```java
record ApprovalCheck(List<String> keys, String reason) {}

default ApprovalCheck approvalCheck(JsonNode args) {
    return isReadOnly() ? null : new ApprovalCheck(List.of(name()), null);
}
```

- Read-only tools return `null` (never prompt) — unchanged behavior.
- Non-read-only tools return `[name()]` (prompt, always-allow key = tool name)
  — unchanged behavior.
- `ShellTool` overrides it: returns `null` for SAFE commands, and
  `ApprovalCheck(classification.keys(), reason)` otherwise, where `reason` is
  `"dangerous command"` if the verdict is DANGEROUS else `"unknown command"`.
  Shell command keys are namespaced with a `shell:` prefix (`shell:git commit`,
  `shell:rm`) so they can never collide with built-in tool names in the shared
  `ToolApproval` set — approving `shell:edit` does not approve the `edit` tool.

Contract: a non-null `ApprovalCheck` always carries at least one key (read-only
tools return `null`; the classifier returns non-empty keys whenever a command
requires approval).

`isReadOnly()` stays on the interface (still used by the registry and tests)
but `ToolLoop` stops branching on it.

### `ToolLoop` changes

`executeTool` replaces the `if (!tool.isReadOnly())` gate with:

```java
Tool.ApprovalCheck check = tool.approvalCheck(call.arguments());
if (check != null) {
    boolean allAllowed = check.keys().stream().allMatch(approval::isAlwaysAllowed);
    if (!allAllowed) {
        ConfirmDecision decision = confirm(call, tool, check, io);
        if (decision == ConfirmDecision.DECLINE) {
            return new ToolResult("User declined to run " + call.name() + ".", true);
        }
        if (decision == ConfirmDecision.ALWAYS_ALLOW) {
            check.keys().forEach(approval::allowAlways);
        }
    }
}
```

The prompt gains the reason suffix when present:

```
Run shell(rm -rf target) (dangerous command) [y/N/a=always]?
```

A chain prompts if *any* of its keys is not always-allowed, and `a` records
*all* of its keys. The existing session-scoped `ToolApproval` (shared with
sub-agents) is unchanged — it just now stores command keys as well as tool
names.

### `ShellConfig` (new, `com.mrsmith.config`)

```java
public record ShellConfig(List<String> harmlessCommands, List<String> dangerousCommands) {
    public static ShellConfig empty() { return new ShellConfig(List.of(), List.of()); }
}
```

- `harmlessCommands` — command specs promoted to SAFE on top of the built-in
  defaults. A one-token entry (`"ps"`) promotes the whole binary; a two-token
  entry (`"kubectl get"`) makes the binary subcommand-aware for that
  subcommand. Tokens beyond the first two are ignored.
- `dangerousCommands` — command specs forced to DANGEROUS, taking precedence
  over the built-in safe list and over `harmlessCommands`. A two-token entry
  (`"mydeploy --push"`) applies only to that binary+subcommand. Tokens beyond
  the first two are ignored.

### Config plumbing

`AgentConfig` gains two fields, `List<String> shellHarmlessCommands` and
`List<String> shellDangerousCommands` (default `List.of()`), parsed by
`ConfigLoader` from flat agent fields:

```json
{
  "name": "coder",
  "tools": ["shell", "read_file", "write_file", "list_dir", "glob", "web_fetch"],
  "shellHarmlessCommands": ["kubectl get", "ps"],
  "shellDangerousCommands": ["mydeploy --push"]
}
```

`ChatCommand` builds a `ShellConfig` from the agent and passes it into a new
`ToolRegistry.with(..., ShellConfig)` overload, which constructs the classifier
and hands it to the `ShellTool`:

```java
// ToolRegistry.with
if (name.equals("shell")) {
    tools.add(new ShellTool(classifier));
} else {
    tools.add(BUILT_INS.get(name).apply(io));
}
```

`ShellTool` keeps its existing constructors (`ShellTool()`,
`ShellTool(Path, long)`) and gains `ShellTool(Path, long, ShellCommandClassifier)`.

### Tool description

The `shell` tool description is updated to mention that read-only commands run
automatically and filesystem-modifying or unknown commands require approval, so
the model knows what to expect.

## Error handling

| Scenario | Behavior |
|---|---|
| All segments SAFE, no redirection | runs, no prompt |
| Any segment DANGEROUS/UNKNOWN, or redirection | prompts unless every key is always-allowed |
| `y` / `yes` | runs once; nothing recorded |
| `a` / `always` | records all keys in the check; runs |
| chain with some keys allowed, some not | prompts (any unapproved key); `a` records all |
| decline / EOF / `IOException` | declined; error result (existing) |
| missing/blank `command` arg | `approvalCheck` returns `null`; `execute` errors as today |
| `web_fetch` private-host approval | unchanged |

## Testing

- `ShellCommandClassifierTest` (new, pure unit tests):
  - safe command → `requiresApproval` false.
  - dangerous (`rm -rf t`) → true, key `rm`.
  - unknown (`frobnicate x`) → true, key `frobnicate`.
  - chain `ls && rm -rf t` → true, keys include `rm`.
  - redirect `cat f > out` → true, key `cat >`; quoted `echo ">"` → false
    (quote-aware).
  - `git status` → false; `git commit -m x` → true, key `git commit`; bare
    `git` or unknown git subcommand → true; `git branch` / `git remote -v`
    → false; `git branch -d` / `git tag -d` / `git remote remove` → true.
  - `find . -delete` / `find -exec` / `sort -o` / `sort --output=` /
    `sort -oout` → true (key `find -delete`, `sort -o`, ...).
  - config: `shellHarmlessCommands: ["kubectl get"]` → `kubectl get` false,
    `kubectl apply` true; `shellDangerousCommands: ["echo"]` overrides built-in
    safe → true.
  - key stripping: `git commit -m x && rm -rf t` → keys `git commit`, `rm`.
- `ShellToolTest`: `approvalCheck` returns `null` for safe, a namespaced key
  (`shell:rm`) for dangerous; keys are namespaced (`shell:edit` does not allow
  the `edit` tool).
- `ChatSessionTest`:
  - safe shell command runs without prompting.
  - dangerous shell command prompts; `a` records the namespaced command key
    (`shell:touch`); a later identical command runs without prompting.
  - chain `a` records each segment key.
  - multi-key always-allow (`MultiKeyTool`) prompts once and records all keys.
  - existing confirm/decline tests pass unchanged.
- `SubAgentRunnerTest`: shared always-allow works with a namespaced command key
  (`shell:touch`) inside a sub-agent.
- `ToolRegistryTest`: registry builds `ShellTool` from a `ShellConfig`.
- `ConfigLoaderTest`: new agent fields parsed (present and absent).
- Full suite stays green.

## Docs

- `README.md`: tools table notes command-level classification for `shell`;
  the config field table documents `shellHarmlessCommands` and
  `shellDangerousCommands`.
