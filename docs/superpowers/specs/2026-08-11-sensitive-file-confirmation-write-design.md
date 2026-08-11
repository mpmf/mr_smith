# Design: Sensitive-File Confirmation for Write Tools

Date: 2026-08-11

## Problem

`write_file` and `edit` are both `isReadOnly() == false`, so the default
`Tool.approvalCheck` (Tool.java:17) returns a check and **prompts on every
call**, including for ordinary in-sandbox writes. This is noisy for routine
work (writing `src/Main.java`). Meanwhile, sensitive files (`.env`, SSH keys,
key/cert files) get no special treatment — they prompt only because *all*
writes prompt, and the reason is generic.

## Goal

Align the write tools with the read tools' sensitive-file behavior:

- **Normal in-sandbox writes** no longer prompt (behavior change — the model
  can write freely within the working directory).
- **Sensitive files** (`.env*`, SSH private keys, key/cert extensions) prompt
  for confirmation, reusing the existing `[y/N/a=always]` flow.
- **Outside the sandbox** remains a hard refusal via `ToolPaths` (unchanged).

## Scope

- `WriteFileTool` and `EditTool` each override `approvalCheck(args)` to return
  an `ApprovalCheck` when the target is sensitive, and `null` otherwise.
- Unit tests for both tools' `approvalCheck`.

## Non-Goals

- No change to the outside-working-directory sandbox: writes that escape the
  root remain a hard refusal (`ToolPaths`), not a prompt. Reading/writing
  outside the sandbox is deferred to a future feature.
- No change to `ToolLoop`, the approval prompt, or `ToolApproval`.
- No change to the read tools (`read_file`, `glob`, `list_dir`) or `SensitivePaths`.
- No change to `shell`.

## Architecture

### `WriteFileTool`

Override `approvalCheck(args)`:

- Parse `path`; if missing/blank return `null`.
- Resolve via `ToolPaths.requireWithin` (name-based, works for non-existent
  files — same pattern as `ReadFileTool`). On `ToolException` (escape) return
  `null` — the sandbox already refuses, so no prompt.
- If `SensitivePaths.isSensitive(target)` → return
  `new ApprovalCheck(List.of(name()), "sensitive file")`.
- Otherwise return `null`.
- `isReadOnly()` stays `false`.

### `EditTool`

Override `approvalCheck(args)`:

- Parse `filePath`; if missing/blank return `null`.
- Resolve via `ToolPaths.requireWithin`; on `ToolException` return `null`.
- If `SensitivePaths.isSensitive(target)` → return
  `new ApprovalCheck(List.of(name()), "sensitive file")`.
- Otherwise return `null`.
- `isReadOnly()` stays `false`.

## Error handling

| Scenario | Behavior |
|---|---|
| Path escapes working directory | hard refusal via `ToolPaths` (unchanged); `approvalCheck` returns `null` |
| Sensitive file targeted | `ApprovalCheck` returned; ToolLoop prompts `[y/N/a=always]` |
| User declines | standard `"User declined to run <tool>."` error (existing) |
| User answers `a`/`always` | tool name remembered for the session (existing) |
| Non-sensitive in-sandbox file | no prompt (behavior change) |

## Testing

- `WriteFileToolTest` (new): normal in-sandbox path → `null`; `.env`,
  `config/id_rsa`, `certs/server.pem` → non-null; escaping path → `null`.
- `EditToolTest` (new): normal file → `null`; `.env` → non-null; escaping path
  → `null`.
- Full suite stays green.
