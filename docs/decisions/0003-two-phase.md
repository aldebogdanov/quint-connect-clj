# 0003 — Generation and replay are separate phases

Status: accepted

## Question

Should `check` be one opaque operation (spawn Quint, parse, replay), or two
composable ones?

## Decision

Two. `quint` produces ITF files. `itf` +
`replay` consume decoded traces and are completely pure. `q/check`
is the small function that composes them.

## Why

- **Regression tests.** A failure is a file. Commit it, and the bug is pinned
  forever with `q/replay-file` — no seed archaeology, no dependence on the
  simulator staying deterministic across Quint versions.
- **CI without Quint.** Committed or cached traces run anywhere with just
  Clojure. Installing the Quint binary becomes an optional part of the build,
  needed only when regenerating traces.
- **Testability.** The interesting logic (decoding, folding, diffing) is tested
  against fixture files with no toolchain in the loop, which keeps the test
  suite fast and hermetic.
- **Debuggability.** When something is wrong you can look at the intermediate
  value: the raw `.itf.json`, the decoded EDN, the result map. Nothing is
  trapped inside one function call.
- **Insurance.** The pure half consumes a plain driver map, knows nothing about
  metadata, and uses no JVM interop. If the reflective layer ever has to be
  replaced — a different wiring style, another runtime — the engine survives it
  untouched. See [0005-clojure-only.md](0005-clojure-only.md).

## Consequences

- Temp-directory lifetime is managed in exactly one place, and `check` keeps
  failing traces instead of deleting them.
- The decoded-trace shape is public API, because users will produce and consume
  it directly.
- Slight cost: traces are materialised as files rather than streamed. Quint
  writes files anyway, so this is not a real loss.
