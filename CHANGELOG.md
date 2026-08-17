# Changelog

Notable changes to quint-connect. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project will
follow [semantic versioning](https://semver.org/) from its first release.

## [Unreleased]

Nothing is released yet. Milestones M1–M6 and M7a of
[docs/roadmap.md](docs/roadmap.md) are complete: the library runs a genuine
model-based test end to end, a failure it finds becomes a committed regression
test, and a scripted Quint `run` drives the implementation too.

### Added

- `itf` — decode Quint's ITF traces to EDN. Handles `#bigint`, `#set`, `#tup`,
  `#map`, records and sum-type variants, plus the bignumber.js form the rust
  backend leaks for integers `>= 10^15`. Normalizes variable names, with a
  `:key-fn` escape hatch and a typed `:name-collision` when two collapse.
- `replay` + `report` — replay a trace against a resolved driver, stopping at
  the first diverging step. Failures carry the step, action, picks, the handler
  var and the reader vars, a `clojure.data/diff`, and a pasteable reproduce
  line.
- `registry` — read `:quint/*` metadata from an explicit `:scan` list and build
  the driver. Supports `:quint/action`, `:quint/args`, `:quint/state` (on vars,
  on reference objects, on getters, with `:path`, and `:*`), `:quint/init` and
  `:quint/halt`. Validates `:empty-scan`, `:duplicate-action`,
  `:duplicate-state`, `:duplicate-init`, `:duplicate-halt` and
  `:ambiguous-arity` at construction. Two vars claiming the same lifecycle role
  — in one namespace or across the `:scan` — are an error rather than
  first-one-wins, because the loser would never run and an `init` that never
  runs reappears as a divergence somewhere unrelated.
- `quint` — run the Quint CLI in a scratch directory and collect the ITF files.
- `core` + `test` — `driver`, `defdriver`, `check`, `replay-file`, and the
  `clojure.test` bridge.
- Failure artifacts — a divergence in `qt/check` writes the trace that caused
  it to `test-resources/quint-connect/failures/`, verbatim, under a
  deterministic name, and the failure message says where. `qt/save-failure!`
  does it to a result you already hold, `:save-failure` turns it off or moves
  it, and `qt/replay-file` asserts on a promoted trace with no Quint involved.
  The workflow is [getting-started.md](docs/getting-started.md) §6.
- Scripted runs — `q/check-run` and `qt/check-run` replay the trace of a named
  Quint `run` through `quint test`. Because those traces carry no `mbt::`
  variables, `:action-path` and `:nondet-path` read the action and the picks
  from ordinary spec variables instead; each path's root variable is split out
  of the compared state, so the implementation is never asked to supply the
  spec's own bookkeeping. A sum-type action name is reachable by ending the
  path at `:tag`. New errors: `:bad-decode-path` and `:test-failed`, the
  latter for a spec whose own `.expect` does not hold.
- The driver's `:key-fn` now reaches the decoder. It was documented in M1 and
  accepted by `itf/itf->trace`, but `check` and `replay-file` never passed it
  down.
- Annotation keys are plain `:quint/*` keywords requiring no `:require`, so an
  annotated namespace takes on no dependency. A driver may move all five at
  once with `:key-ns`.

### Known gaps

- `:missing-state` is not implemented. A spec variable that no reader supplies
  shows up as a diff against nothing rather than a typed error. The driver
  never sees the spec, so this can only be caught at the first comparison.
- `quint verify` is not wired up. Its traces carry no `mbt::` variables, so
  they will drive an implementation the same way `quint test` traces already
  do — through `:action-path` — but the mode itself is M7b.
- An annotation stranded under the wrong `:key-ns` is ignored silently unless
  the whole namespace scans empty. See
  [0007](docs/decisions/0007-annotation-keys.md).
- No `:setup`/`:teardown` hook, and none is planned until something needs one:
  a fixture that must run once per `check` is `clojure.test/use-fixtures` or a
  `let` around the call.
