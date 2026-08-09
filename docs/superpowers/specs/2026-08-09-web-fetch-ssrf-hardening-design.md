# Design: SSRF Hardening for `web_fetch`

Date: 2026-08-09

## Problem

`WebFetchTool` validates only the URL scheme (`http://`/`https://`) before
fetching. The agentic harness can therefore instruct `web_fetch` to hit
private, link-local, or localhost targets — cloud metadata services
(`http://169.254.169.254/`), internal dashboards, local daemons, etc. This is a
server-side request forgery (SSRF) risk inherent to an agent that can fetch
arbitrary URLs. Additionally, the client follows redirects
(`HttpClient.Redirect.NORMAL`), so even a future host check could be bypassed by
a public URL that 302-redirects to a private host.

## Goal

When `web_fetch` is asked to fetch a private/link-local/localhost host, ask the
user for approval before making the request. If the user does not approve,
return a tool result telling the model that the user did not approve fetching
that private/link-local/localhost host, so the model can adjust. Redirects are
followed manually and every hop re-checked, so the check cannot be bypassed via
a redirect.

## Scope

- Host classification (`isPrivateHost`) covering localhost and the standard
  private/link-local/loopback ranges, applied to the URL's host before fetching.
- An inline `[y/N]` approval prompt when a private host is detected; decline
  (or EOF/error) yields a clear tool result rather than a fetch.
- Manual redirect following (replacing `Redirect.NORMAL`) with the host check
  re-applied at every hop, up to a hop limit.
- `WebFetchTool` gains an `IO` dependency; `ToolRegistry` wiring updated so the
  built-in `web_fetch` is constructed with the session's `IO`.

## Non-Goals

- No DNS resolution of plain hostnames (a hostname that *resolves* to a private
  IP is not detected — documented limitation; see Error Handling).
- No configuration flag to allow/allowlist internal hosts.
- No changes to the other tools, the `Tool` contract, or the read-only status of
  `web_fetch` (it stays read-only; its private-host prompt is internal).
- No rate limiting or blocklist persistence.

## Architecture

### Host classification

`WebFetchTool` gains a static `isPrivateHost(String host)` check applied to the
URL's host (from `URI.getHost()`):

1. Lowercase the host. `localhost` and any `*.localhost` host is private.
2. If the host is an IP literal — IPv4 (`\d+(\.\d+){3}`) or IPv6 (contains `:`)
   — resolve it with `InetAddress.getByName` (a pure parse for literals; **no
   DNS lookup is performed**). Treat it as private when any of:
   - `isLoopbackAddress()` — `127.0.0.0/8`, `::1`
   - `isAnyLocalAddress()` — `0.0.0.0`, `::`
   - `isLinkLocalAddress()` — `169.254.0.0/16` (incl. metadata IP
     `169.254.169.254`), `fe80::/10`
   - `isSiteLocalAddress()` — `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`,
     `fc00::/7`
3. Plain hostnames (not IP literals) are not classified as private.

### Approval flow

`WebFetchTool` takes an `IO`:

- New constructors: `WebFetchTool(IO)` (defaults: `HttpClient` + 10s timeout)
  and `WebFetchTool(HttpClient, long timeoutMillis, IO)`.
- When `isPrivateHost` returns true, print via `io.writePrompt`:
  `web_fetch: <url> targets a private/link-local/localhost host. Fetch anyway? [y/N] `
  then read one line.
- `y`/`yes` (case-insensitive) → proceed with the fetch.
- Anything else, `null` (EOF), or an `IOException` → do not fetch; return
  `ToolResult("User did not approve fetching <url> (private/link-local/localhost host).", true)`
  (error result, matching how the loop surfaces user-declined mutations).
- The approval decision is cached per host for the duration of a single
  `execute(...)` call, so a same-host redirect hop does not re-prompt.

### Manual redirects

- The `HttpClient` must not auto-follow redirects. The default
  `HttpClient.newHttpClient()` uses `Redirect.NEVER` (replacing the current
  explicit `Redirect.NORMAL`); an injected test client must likewise be built
  with `Redirect.NEVER` so 3xx responses reach the manual follower.
- `execute` sends the request; if the response is a 3xx with a `Location`
  header, resolve `Location` against the current URI, re-run the private-host
  check + approval on the new target, and follow up to 5 hops.
- More than 5 hops → `ToolResult("too many redirects", true)`.
- A redirect to an already-approved host (same host string as a previously
  approved hop in this call) does not re-prompt.

### `ToolRegistry` wiring

`ToolRegistry.BUILT_INS` changes from `Map<String, Supplier<Tool>>` to
`Map<String, Function<IO, Tool>>`:

```java
BUILT_INS.put("shell", io -> new ShellTool());
...
BUILT_INS.put("web_fetch", WebFetchTool::new); // ctor WebFetchTool(IO)
```

`ToolRegistry.with(...)` builds each named tool with `factory.apply(io)`.
`builtinNames()` (the config-validation set) is unchanged. The always-added
tools (`edit`, `todowrite`, `question`, `skill`, `task`) are untouched.

## Data flow

```
execute(url)
  → validate scheme (unchanged)
  → uri = URI.create(url); host = uri.getHost()
  → if host == null → ToolException("invalid url")
  → if isPrivateHost(host) and host not yet approved this call:
        prompt "[y/N]"
        decline → ToolResult("User did not approve fetching <url> (private/...).", true)
  → send request
  → if 3xx and Location present and hops < 5:
        newUrl = uri.resolve(Location)
        re-check private host (approve prompt per new host if needed)
        follow; hops++
  → else if 3xx and hops >= 5 → ToolResult("too many redirects", true)
  → read body (existing size limit + truncation) → ToolResult
```

## Error handling

| Scenario | Behavior |
|---|---|
| Private host, user approves (`y`/`yes`) | fetch proceeds |
| Private host, user declines / EOF / `IOException` | no fetch; `ToolResult("User did not approve fetching <url> (private/link-local/localhost host).", true)` |
| Public host | no prompt; fetch as today |
| Redirect to a private host | prompt for the new host; decline stops the chain |
| Redirect chain > 5 hops | `ToolResult("too many redirects", true)` |
| Plain hostname resolving to a private IP | not detected (no DNS lookup) — documented limitation |
| Non-literal malformed IP string | treated as non-private; the fetch itself fails normally if invalid |

## Testing

`WebFetchToolTest` gains a small `StubIo` (queued lines; helper `approve()` /
`decline()`). Existing tests' URLs come from `MockWebServer`, which binds to
`localhost`, so every existing fetch test is updated to inject an auto-approving
`StubIo`:

- Existing: `fetchesBodyText`, `followsRedirects` (now exercising manual
  follow), `returnsErrorOnHttp4xx`, `timesOutWhenServerStalls`,
  `malformedUrlThrowsToolException` (constructor updated to pass an `IO`).
- New:
  - `localhostDeclineReturnsNotApproved` — `http://localhost:PORT/...`,
    decline → error result containing "did not approve".
  - `localhostApproveFetches` — approve → body returned.
  - `privateIpDecline` — `http://10.0.0.5/`, `http://192.168.1.1/`,
    `http://172.16.0.1/`, `http://169.254.169.254/`, `http://[::1]/` → prompt
    fires, decline → not-approved result.
  - `publicHostFetchesWithoutPrompt` — a public-looking URL (`http://example.com/...`)
    through an injected stub `HttpClient` (no real network) → no prompt, body
    returned.
  - `redirectToPrivateHostPrompts` — stub `HttpClient` returns 302 `Location:
    http://169.254.169.254/` → prompt fires for the redirect target; decline →
    not-approved result.
  - `sameHostRedirectDoesNotReprompt` — stub `HttpClient` returns 302 to a
    same-host URL after one approval → only one prompt.
  - `tooManyRedirects` — stub `HttpClient` loops redirects → "too many
    redirects" error.
