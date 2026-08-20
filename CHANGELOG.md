# Changelog

Notable changes to quint-connect. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project will
follow [semantic versioning](https://semver.org/) from its first release.

## [Unreleased]

Nothing since 0.5.0.

## [0.5.0] — 2026-08-21

### Added

- **`:quint/args` composes any shape the handler takes apart.** An entry is a
  pick name, or a vector or map of entries, nesting as deep as the parameter
  list does — the exact inverse of destructuring:

  ```
  handler                        :quint/args
  [{:keys [from to]}]            [{:from :src :to :dst}]
  [[x y]]                        [[:x :y]]
  [{:keys [pos]}]                [{:pos [:x :y]}]
  [[{:keys [a]} b]]              [[{:a :pa} :b]]
  ```

  Additive: a flat vector of pick names, and the flat map 0.4.0 introduced, are
  both exactly what they were. Sets are rejected — nothing destructures one
  positionally — and composed map keys must be keywords, which is a restriction
  rather than a necessity and is written down as one in
  [0011](docs/decisions/0011-args-compose-shapes.md).

### Fixed

- The README documented `:quint/args` nowhere at all — not the map form 0.4.0
  added, and not the plain vector form that predates it. It now carries the
  whole key, including the two shapes that look alike: destructuring one pick
  whose value is a record or tuple (`:quint/args [:m]`) versus wanting the
  whole picks map (the driver map's `:actions`).

## [0.4.0] — 2026-08-20

The changes below reject annotations that 0.3.0 accepted and then failed to
read, so a driver that resolved before may now throw. That is the point — every
one of these was a divergence with no cause attached to it — but it is a
breaking change and gets a minor bump rather than a patch.

### Added

- `:bad-arglist` — an `:quint/action` whose arglist cannot name picks. Three
  cases, three messages: a destructuring parameter, a rest parameter, and no
  `:arglists` at all. The last one distinguishes `(def f (fn ...))`, which
  records none, from a var that holds no function, which cannot handle an
  action at all; 0.3.0 called both of those "has 0 arities".
- `:bad-state-spec` — a `:quint/state` map that would go partly unread: an
  unknown key, a missing `:var`, or a `:path` that is not a vector.
- `:quint/args` may build an argument out of picks. An entry is a pick name, or
  a map whose keys name what the handler destructures and whose values name the
  picks they come from:

  ```clojure
  (defn transfer
    {:quint/action "transfer" :quint/args [{:from :src :to :dst :amount :amt}]}
    [{:keys [from to amount]}]
    ...)
  ```

  A function whose own signature takes a map could not be annotated at all
  before — positional binding has no name to bind, and `:actions` meant moving
  the mapping away from the code it describes. The two entry forms compose.
  This is a value form, not a seventh key; the vocabulary is still six. The
  rule it was spent against is recorded in
  [0010](docs/decisions/0010-args-shapes.md).
- `:bad-args` — at construction, a `:quint/args` that is not a vector or whose
  entry is neither a pick name nor a map of keyword to keyword.
- **A pick the handler needs that the trace does not carry is now an error at
  replay**, from either source: `:bad-args` when the name was written into
  `:quint/args`, `:bad-arglist` when it came from the parameter list. A
  parameter misspelled against the spec bound nil exactly as a misspelled
  annotation did. Names beginning with `_` are never checked — that is already
  the documented way to ignore a pick, and three of the four examples rely
  on it.
- `:path` on a `:*` reader. A whole state map is as likely to sit nested inside
  a system map as a single variable is — which is the shape a Choreo-style
  spec's per-node state arrives in — so `{:var :* :path [:app :state]}` now
  reads the map from there instead of ignoring the path.

### Changed

- `registry` split: the checks moved to `registry.validation`, leaving
  `registry` with the reading — `ns-interns`, `meta`, `deref` — and validation
  with what the reading is allowed to mean. 278 lines became 157 and 159.
  Nothing in validation reflects, so the rule confining reflection to
  `registry` is unchanged. Private namespace, no public API moves.

### Fixed

- **A destructuring handler silently received nil.** `keyword` returns nil
  rather than throwing on a destructuring form, so `(defn transfer
  {:quint/action "transfer"} [{:keys [from to amount]}] ...)` resolved, ran,
  and bound every pick to nil. Now `:bad-arglist`, naming the driver map's
  `:actions` as the place a picks-map handler does belong. A variadic handler
  bound its rest parameter the same way.
- **A misspelled key in a `:quint/state` map silently dropped the variable.**
  `{:variable :balances}` left `:var` nil, so the reader supplied `{nil ...}`,
  and since only the trace's own variables are compared, that spec variable was
  never checked and nothing said so. Now `:bad-state-spec`.
- **A `:path` that was not a vector threw `IllegalArgumentException`**, with no
  `:quint/error` on it. The decoder had this guard; the registry did not.
- **`:quint/args` was not validated at all.** `:quint/args :who` resolved and
  then threw `IllegalArgumentException` at replay; `:quint/args [:wo :amount]`
  resolved, ran, and bound `nil`. The second is the sharper one: `:quint/args`
  exists for when parameter names differ from pick names, so a typo in it lands
  where nobody is looking, and the failure blamed the implementation for a
  state the picks had never reached. Both are `:bad-args` now.
- **Two `:*` readers supplying one variable resolved silently**, with the
  winner decided by `ns-interns` hash order rather than by anything the author
  wrote. It is now `:duplicate-state`, raised by `replay/run-trace` rather than
  at construction: a `:*` reader declares nothing, so what it covers is only
  knowable once it has been called, and calling readers at construction — before
  `:quint/init` has run — is worse than the bug. Driver-map `:state` entries
  carry `:override?` and are exempt, because replacing a reader is what they
  are for.

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

  The scope names a driver rather than a spec because a driver map already
  carries `:spec`, so naming the spec would be a second copy of what the driver
  already states — and two drivers can share one spec while wanting different
  lifecycle. What it does leave open is a typo: `:quint/driver :ledgr` is read
  by nobody and reported by nobody, which is accepted on the same terms as the
  stranded `:key-ns`.

  This key was added **without** the real spec [CLAUDE.md](CLAUDE.md) asks for.
  That is deliberate and recorded in
  [0009](docs/decisions/0009-driver-scope.md) §"The rule this was added
  against", including the risk taken.

### Changed

- `:empty-scan` now means "no annotations at all" rather than "nothing for this
  driver". A namespace whose annotations are all scoped to other drivers is
  legitimately empty for this one and is no longer reported as the
  `^{...} (defn ...)` trap.

## [0.2.0] — 2026-08-18 (never published)

Cut but not released: `:quint/driver` landed before it went out, and it shipped
as part of 0.3.0 instead. There is no `v0.2.0` tag and no Clojars artifact; the
entry is kept because the work is real and 0.3.0's notes assume it.

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
