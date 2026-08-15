# 0004 — Shell out to the Quint CLI; keep dependencies near zero

Status: accepted

## Decision

quint-connect invokes the `quint` binary as a subprocess and reads the ITF JSON it
writes. It does not embed, reimplement, or link to Quint. Runtime dependencies
are Clojure itself plus `org.clojure/data.json`.

## Why

Quint's simulator is the product of a team and now has a Rust backend. Traces
are the interoperability boundary the Quint project itself designed for — ITF
is a published format (ADR-015), and quint-connect uses exactly this approach.
Shelling out means we inherit every Quint improvement for free and stay
uninvolved in its evolution.

JSON parsing: `data.json` is pure Clojure with no transitive dependencies, and
it is hidden behind one function in `uno.michelada.quint-connect.itf`, so swapping it is a
one-line change. No Cheshire, no Jackson, no transit — a testing library that
drags a JSON stack into a user's dependency tree is a bad neighbour.

Process invocation needs no dependency at all: `clojure.java.process` ships with
Clojure 1.12.

## Version policy

Developed and tested against **Quint 0.32.0**. The CLI surface we depend on:

- `quint run --mbt --seed --n-traces --max-samples --max-steps --out-itf --verbosity`
- `quint test --match --out-itf` (no `--mbt` support; see notes/itf-format.md)
- `quint verify --invariant --out-itf`

`uno.michelada.quint-connect.quint` checks `quint --version` once per process and warns — not
fails — on a different version. Fixtures in `dev/fixtures/` record what 0.32.0
actually produced, so a format change shows up as a failing decode test rather
than a confusing runtime error.

## Consequences

- Trace generation costs a process spawn. Amortised over N traces per
  invocation, this is irrelevant.
- Quint's own error output must be surfaced verbatim; wrapping it in our own
  phrasing would hide the useful part.
