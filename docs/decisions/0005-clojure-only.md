# 0005 — Clojure on the JVM only

Status: accepted

## Decision

quint-connect targets Clojure on the JVM. ClojureScript is not supported, not
partially supported, and not planned. Source files are `.clj`, not `.cljc`.

## Why

Two reasons, and the second is the decisive one.

**Scope.** Supporting ClojureScript would have meant reader conditionals in the
JSON and process-spawning layers, a second test pipeline, a node build, a
browser-only degraded mode, and a class of bug that only appears on one
platform — all before the first useful feature existed.

**The wiring design requires it.** The mapping from spec to implementation is
declared with var metadata and read back by scanning namespaces
([0002-metadata-wiring.md](0002-metadata-wiring.md)). ClojureScript has no
`ns-interns` at runtime; namespaces are not reified objects you can enumerate.
So the declarative style this project is built around is JVM-only by
construction. Given a choice between the wiring design and cross-platform
support, the wiring design won, and this ADR records that trade deliberately
rather than leaving it implicit.

## What it buys

- `ns-interns` + `meta`: annotations, with no registration boilerplate.
- `clojure.java.process` (Clojure 1.12, no dependency) instead of a subprocess
  layer with two implementations.
- `clojure.data.json` used directly, no JSON abstraction.
- `long` / `BigInt` integer semantics, decided once.
- One test command, one CI job, one set of fixtures with one expected result.

## What it costs

- No model-based testing of ClojureScript front ends.
- If that is ever wanted, it cannot be a port of this design — it would need
  explicit registration instead of scanning. What *would* carry over is the pure
  half: `itf`, `replay` and `report` consume a
  plain driver map, know nothing about metadata, and use no JVM interop. That
  separation is deliberate insurance, not a promise.

## Consequences

- No `.cljc` files, no reader conditionals.
- JVM interop is allowed wherever it is the simplest answer, but the three pure
  namespaces should not need it, and a JVM call appearing in one of them is a
  review question.
