# Design: Time-Ordered Session IDs (UUID v7)

Date: 2026-08-03

## Problem

Session folders are named with `UUID.randomUUID()` (v4), which is time-opaque. A
browsing the sessions directory cannot tell which session is older or newer, and
listing order does not reflect creation order.

## Goal

Session IDs should remain opaque but be time-ordered, so the sessions directory
sorts chronologically.

## Approach

Add a small `UuidV7` utility in `com.mrsmith.chat` that generates RFC 9562
version 7 UUIDs:

- bits 0-47: Unix epoch milliseconds (48 bits)
- bits 48-51: version field `0111`
- bits 52-63: variant field `10` (RFC 4122)
- bits 64-127: 64 random bits (sub-millisecond disambiguation)

Java 21 has no built-in v7 generator (that arrived in Java 25), so this is a
~20-line hand-rolled implementation. No new dependencies.

### Changes

- Add `src/main/java/com/mrsmith/chat/UuidV7.java` with a `random()` factory.
- Replace `UUID.randomUUID()` at `ChatSession.java:113` (in
  `startNewSession()`) with `UuidV7.random()`.
- No other changes: the ID remains opaque, only its ordering characteristics
  change.

### Behavior

- Two sessions created in different milliseconds sort strictly by creation time.
- Two sessions created within the same millisecond are not guaranteed to be
  strictly monotonic (random tie-break). This is accepted: sessions are created
  seconds apart in practice, and the primary requirement is chronological
  sorting, not a total order.
- Existing v4-named session folders are unaffected; they remain valid but simply
  predate v7 naming.

## Testing

New `src/test/java/com/mrsmith/chat/UuidV7Test.java`:

- version bits == 7
- variant bits set correctly (RFC 4122)
- matches the canonical `8-4-4-4-12` string shape
- two IDs generated sequentially are ordered (first < second), reflecting the
  timestamp prefix

## Non-Goals

- No per-process monotonic counter for same-ms ordering.
- No migration/renaming of existing session folders.
- No changes to session content, transcripts, or lookup.
