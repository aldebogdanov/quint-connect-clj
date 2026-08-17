# 0001 — A library of functions, not a test runner

Status: accepted

## Question

Kaocha plugin, or a standalone `quint-connect` test runner?

## Decision

Neither, first. The core is a library whose entry point is an ordinary
function: `(q/check driver opts)` -> result map. A thin `clojure.test` bridge is
provided in `test`. A Kaocha plugin may follow later as a separate,
optional artifact.

## Why

A bespoke runner would mean re-implementing test selection, watch mode,
reporting, JUnit output, CI integration and editor integration — none of which
is the interesting part of this project, and all of which already exists.

Going through `clojure.test` gets all of it for free, and means quint-connect works
with Kaocha, `cognitect.test-runner`, CIDER and Calva without knowing about any
of them.

A Kaocha plugin can only add presentation: nicer diffs, per-trace progress, seed
reporting. That is worth having eventually, but it is a leaf, not a foundation.
Putting it in the core would also make Kaocha a dependency of every user,
including those who do not run Kaocha.

The function-first shape also matters at the REPL: `check` returns data, so you
can inspect a failure, tweak the driver map, and re-run without a test framework
in the loop at all.

## Consequences

- `test` must keep its assertion output useful, since that is where
  most users will meet failures.
- The result map is public API and gets treated as such.
- A Kaocha plugin, if it happens, lives in its own directory with its own
  `deps.edn` so the core stays at one dependency.
