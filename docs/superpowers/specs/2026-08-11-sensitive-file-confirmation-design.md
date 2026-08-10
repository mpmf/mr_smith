# Design: Sensitive-File Confirmation for File-Inspection Tools

Date: 2026-08-11

## Problem

The file-inspection tools `read_file`, `glob`, and `list_dir` are all
`isReadOnly() == true`, so `Tool.approvalCheck` (Tool.java:17) returns `null`
and none of them ever prompt. A model can therefore read or enumerate sensitive
files inside the working directory — most notably `.env` and other secret
files — with no user confirmation. The sandbox already refuses paths that
escape the working directory (`ToolPaths`), but it does not distinguish
sensitive files *within* the sandbox.

## Goal

Prompt the user for confirmation before a file-inspection tool touches a
sensitive file inside the working directory. Sensitive files are `.env` /
`.env.*` and well-known secret files (`id_rsa`, `*.pem`, `*.key`, `*.p12`,
`*.pfx`, `*.jks`, `*.keystore`, etc.). The existing `y/N/a=always` approval
prompt is reused; no new prompt UI.

## Scope

- A shared sensitive-path matcher (`SensitivePaths`) used by all three tools.
- `read_file`, `glob`, and `list_dir` each override `approvalCheck(args)` to
  return an `ApprovalCheck` when the target is sensitive.
- Unit tests for the matcher and each tool's `approvalCheck`.

## Non-Goals

- No change to the outside-working-directory sandbox: paths that escape the
  root remain a hard refusal (`ToolPaths`), not a prompt.
- No change to `ToolLoop`, the approval prompt, or `ToolApproval`.
- No change to write tools (`write_file`, `edit`, `shell`).
- No new sensitive-file categories beyond the list below.
- No redaction or masking of file contents — only confirmation before access.

## Architecture

### `SensitivePaths` (new)

`com.mrsmith.tool.SensitivePaths` — a package-private final utility class,
mirroring `ToolPaths`'s style:

```java
final class SensitivePaths {
    static boolean isSensitive(Path path) { ... }
}
```

Classification is by **filename** (the last path element), case-insensitive.
A path is sensitive if its filename matches any of:

- `.env` or `.env.*` (any name starting with `.env`)
- `id_rsa`, `id_dsa`, `id_ecdsa`, `id_ed25519` (with or without an extension)
- ends in `.pem`, `.key`, `.p12`, `.pfx`, `.jks`, or `.keystore`

### `ReadFileTool`

Override `approvalCheck(args)`:

- Parse `path`; resolve via `ToolPaths.requireWithin` + `requireCanonicalWithin`
  (same as `execute`). If resolution throws `ToolException` (escape or missing),
  return `null` — the sandbox already refuses, so no prompt.
- If `SensitivePaths.isSensitive(resolved)` → return
  `new ApprovalCheck(List.of(name()), "sensitive file")`.
- Otherwise return `null`.
- `isReadOnly()` stays `true`.

### `ListDirTool`

Override `approvalCheck(args)`:

- Resolve the directory as in `execute`; on `ToolException` return `null`.
- If the directory itself is sensitive → return a check.
- Else list the entries; if **any** entry filename is sensitive → return a
  check (a listing could reveal a `.env` filename).
- Otherwise return `null`.

### `GlobTool`

Override `approvalCheck(args)`:

- Conservative "could match" heuristic. Return a check when the glob pattern
  could plausibly match a sensitive filename.
- Concretely: return a check if the pattern contains any of `*`, `?`, `[`,
  `{`, or a `**` segment. A literal path with no wildcards (e.g. `README.md`)
  returns `null`.
- Over-prompting on broad patterns (e.g. `src/**/*.java`) is acceptable;
  under-prompting is not.

## Error handling

| Scenario | Behavior |
|---|---|
| Path escapes working directory | hard refusal via `ToolPaths` (unchanged); `approvalCheck` returns `null` |
| Sensitive file targeted | `ApprovalCheck` returned; ToolLoop prompts `[y/N/a=always]` |
| User declines | standard `"User declined to run <tool>."` error (existing) |
| User answers `a`/`always` | tool name remembered for the session (existing) |
| Non-sensitive file | no prompt |

## Testing

- `SensitivePathsTest` (new): matcher unit tests — `.env`, `.env.local`,
  `config/id_rsa`, `certs/server.pem`, `a.key`, `b.p12` are sensitive;
  `README.md`, `src/Main.java`, `notes.txt` are not; case-insensitivity.
- `ReadFileToolTest` (new): normal file → `null`; `.env`, `.env.local`,
  `config/id_rsa`, `certs/server.pem` → non-null; escape path → `null`.
- `ListDirToolTest` (new): empty dir → `null`; dir containing `.env` →
  non-null; dir with only normal files → `null`; missing dir → `null`.
- `GlobToolTest` (new): literal `README.md` → `null`; `*`, `**/*.java`,
  `*.env` → non-null.
- Full suite stays green.
