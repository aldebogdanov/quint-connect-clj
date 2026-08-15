# Changelog

Notable changes to quint-connect. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project will
follow [semantic versioning](https://semver.org/) from its first release.

## [Unreleased]

Nothing is released yet. Milestones M1–M5 of
[docs/roadmap.md](docs/roadmap.md) are complete: the library runs a genuine
model-based test end to end.

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
  `:duplicate-state` and `:ambiguous-arity` at construction.
- `quint` — run the Quint CLI in a scratch directory and collect the ITF files.
- `core` + `test` — `driver`, `defdriver`, `check`, `replay-file`, and the
  `clojure.test` bridge.
- Annotation keys are plain `:quint/*` keywords requiring no `:require`, so an
  annotated namespace takes on no dependency. A driver may move all five at
  once with `:key-ns`.

### Known gaps

- `:missing-state` is not implemented. A spec variable that no reader supplies
  shows up as a diff against `{}` rather than a typed error.
- Only `quint run --mbt` carries action metadata; `quint test` and
  `quint verify` traces cannot drive an implementation yet.
- An annotation stranded under the wrong `:key-ns` is ignored silently unless
  the whole namespace scans empty. See
  [0007](docs/decisions/0007-annotation-keys.md).
