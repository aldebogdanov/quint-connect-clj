# Changelog

Notable changes to quint-connect. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project will
follow [semantic versioning](https://semver.org/) from its first release.

## [Unreleased]

Nothing since 0.3.0.

## [0.3.0] — 2026-08-18

### Added

- `:quint/driver` — a sixth annotation key, scoping an annotation to named
  drivers, with `:name` in the driver map to match it against. One namespace
  can now serve two specs that each want their own `init`, which was
  `:duplicate-init` before. A keyword or a set; **absent means every driver**,
  so nothing written before this changes. It moves with `:key-ns` like the
  other five, and scoping happens before duplicate detection — two inits for
  two drivers are not a collision, two for the same one still are.
- `:unnamed-driver` — a scoped annotation in a driver with no `:name`. The
  question it asks has no answer, and guessing either way is silent.

  This key was added **without** the real spec [CLAUDE.md](CLAUDE.md) asks for.
  That is deliberate and recorded in
  [0009](docs/decisions/0009-driver-scope.md) §"The rule this was added
  against", including the risk taken.

### Changed

- `:empty-scan` now means "no annotations at all" rather than "nothing for this
  driver". A namespace whose annotations are all scoped to other drivers is
  legitimately empty for this one and is no longer reported as the
  `^{...} (defn ...)` trap.

## [0.2.0] — 2026-08-18

`verify` (M7b), which completes the roadmap: every planned milestone is done.

### Added

- `q/verify` and `qt/verify` — check an invariant with Apalache through
  `quint verify`, and replay the counterexample against the implementation when
  it does not hold. A violated invariant is a failure whether or not the
  implementation agrees with the counterexample, because those are two facts:
  `:invariant` says the spec's own property does not hold, and `:failure` says
  the implementation diverged from the counterexample. A nil `:failure` there
  means the code reproduces the spec's bug faithfully, and the message says so.
- `quint/verify!` — the CLI half. The outcome is not in the exit code: holding
  exits 0 and everything else exits 1, so this branches on whether a trace was
  written. An invariant that holds writes none, which is why it cannot reuse
  `:no-traces`.
- Counterexamples are saved like divergences, named
  `<spec>-<invariant>-counterexample.itf.json` — after the invariant rather
  than a seed, because Apalache rolled no dice and re-checking rewrites the
  same file. Saved even when nothing diverged.
- `bb test:verify`, and a `^:slow` tag that keeps Apalache out of `bb test` and
  `bb test:all`.
- `dev/fixtures/tracked.qnt` gained `noNegatives` and `underFifty`, and
  `tracked_verify_underFifty.itf.json` is a recorded counterexample that
  replays with no Apalache installed.

### Changed

- `quint verify` runs in a scratch working directory instead of the spec's own,
  because Apalache writes an `_apalache-out/` log directory into wherever it is
  invoked. It is deleted with the scratch directory, so the spec directory
  stays clean — there is a test for exactly that.
- The reproduce line now keeps the ITF option's basename rather than
  reconstructing it, which was wrong for `verify`'s single output file.

## [0.1.0] — 2026-08-18

First release, as `org.clojars.aldebogdanov/quint-connect`. Milestones M1–M6
and M7a of [docs/roadmap.md](docs/roadmap.md) are complete: the library runs a
genuine model-based test end to end, a failure it finds becomes a committed
regression test, and a scripted Quint `run` drives the implementation too.

`verify` (M7b) is **not** in this release; the mode is designed and its
behaviour recorded, but unbuilt. See "Known gaps" below before filing anything.

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
- An annotation stranded under the wrong `:key-ns` is ignored silently unless
  the whole namespace scans empty. See
  [0007](docs/decisions/0007-annotation-keys.md).
- No `:setup`/`:teardown` hook, and none is planned until something needs one:
  a fixture that must run once per `check` is `clojure.test/use-fixtures` or a
  `let` around the call.
