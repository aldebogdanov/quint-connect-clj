# Changelog

Notable changes to quint-connect. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project will
follow [semantic versioning](https://semver.org/) from its first release.

## [Unreleased]

Nothing since 0.7.0.

## [0.7.0] — 2026-10-01

### Added

- **Specs written with [Choreo](https://github.com/informalsystems/choreo).**
  Choreo keeps all of a spec's state in one variable, `s`, and names none of
  its transitions: under `--mbt` every step is `"step"`, and `quint test`
  records no picks at all. The spec now records each transition in
  `s.extensions` — the convention quint-connect (Rust) uses — and the driver
  map says where things are:

  ```clojure
  {:state-path  [:s]
   :action-path [:extensions :actionTaken :tag]
   :nondet-path [:extensions :actionTaken :value]}
  ```

  That reaches `check`, `check-run` and `replay-file`, reports coverage per
  transition, and lets the implementation's own functions be annotated with the
  transitions they implement. The instrumentation keeps Choreo's rule of
  dropping transitions that change nothing — without it three steps in four
  repeated an instruction already acted on — and with that, `:max-samples`
  above `:traces` steers Quint toward a protocol's deepest paths, since it
  writes the longest of its attempts. The recipe is [docs/choreo.md](docs/choreo.md);
  the reasoning is [0013](docs/decisions/0013-choreo-state-path.md).

- **`:temporal` for `verify`**: check a `temporal` definition — Quint 0.33.0's
  action properties included — where `:invariant` checks a `val`. **It checks
  the spec only**: no trace comes back from TLC, so the implementation is never
  run. It needs
  `:backend :tlc` and is `:bad-options` without it: under Apalache Quint asks
  on stdin whether to go ahead, and unanswered it waits for ever, or, with
  stdin closed, exits 0 having checked nothing — recorded with
  `dev/probes/temporal_probe.sh`. It is `:bad-options` alongside `:invariant`
  too, since Quint gives the two one verdict. A holding property is `:ok? true`
  with `:temporal {:name .. :holds? true}`; a violated one is `:quint-failed`
  quoting Quint's `found a counterexample`, as TLC writes no trace to replay.

- **`:state-path`** in the driver map. The compared state becomes the record
  found there, and its fields stand in for spec variables, so a reader is
  annotated `{:quint/state :system}` as it would be for a spec with a variable
  of that name. `:action-path` and `:nondet-path` are read inside it.
  `:bad-decode-path` when it is not a vector, finds nothing — the message then
  lists the variables there are — or finds something that is not a record.

- **The empty tuple at `:nondet-path` is no picks.** A variant without an
  argument, such as Choreo's `Init`, carries it as its value.

- [examples/two-phase-commit/](examples/two-phase-commit/): Choreo's own
  two-phase commit, instrumented, driving a Clojure implementation under
  `check` and `check-run`, with a participant that ignores an abort and a
  coordinator that forgets to broadcast as its broken versions. CI runs it.

- Choreo vendored at a pinned commit, with its Apache-2.0 licence, in
  `dev/fixtures/choreo/` and in the example; nothing of it reaches the jar.
  See [0012](docs/decisions/0012-vendor-choreo.md). Four recordings of it, and
  [`dev/probes/choreo_probe.sh`](dev/probes/choreo_probe.sh) to re-check what
  they show.

### Changed

- **`itf` is split in two.** It reached 339 lines with `:state-path`, and
  `itf.paths` now holds what the driver steers: reading a decoded state
  through `:state-path`, `:action-path` and `:nondet-path`. `itf/itf->trace` is
  unchanged. The ~200-line rule in CLAUDE.md is now a recommendation: split
  where there is a seam, and where there is none, say why in
  [architecture.md](docs/architecture.md) §3 — which now does, for `quint`,
  `replay` and `registry.validation`.

- **Quint 0.33.0 or later is required**, and an older one is `:quint-too-old`
  rather than a warning. 0.32.0 does not fail; it writes traces that decode
  cleanly and are wrong. Its rust evaluator left a dead-ended sample's action
  and picks in storage, where the next sample's `init` could not overwrite
  them, so state 0 of later traces said `"step"` with picks no transition used
  (Quint #2012, fixed in 0.33.0). A driver handling `"step"` itself — Choreo as
  written — was handed the initial state instead of `init` running. Every
  fixture re-recorded on 0.33.0 identically but for timestamps, except the one
  that records the bug. CI installs 0.33.0, whose Apalache 0.62.1 needs Java 21.
  See [0014](docs/decisions/0014-quint-floor.md).

### Fixed

- **A diverging `check-run` is named after its run.** It was headed
  `diverged on trace 0 of 1, seed ` — with nothing after "seed" — and saved as
  `<spec>-seed-trace0.itf.json`, because both were written for random runs,
  where the seed is what identifies a trace. A scripted run is identified by
  its name and has a seed only if one was passed. Now: `diverged on run
  "commitTest"`, saved as `<spec>-commitTest.itf.json`, and the result carries
  `:test`. No seed is generated for it: `quint test` tries a randomized run
  once when given a seed and up to 10000 times without one, so inventing one
  would change what the run tests.

- **`verify` under `:backend :tlc` no longer calls a violation a broken
  setup.** Quint writes `--out-itf` only from Apalache, so a TLC counterexample
  exits 1 with no trace — which `verify!` reported as "the invariant was never
  checked; the spec or the invariant name is the likely cause". It is still
  `:quint-failed`, since there is nothing to replay, but the message now says
  what that outcome means under TLC and quotes Quint's first line, which is
  where the verdict is.

- **Paths that leave nothing to compare are refused instead of passing.**
  `:action-path [:s :extensions :actionTaken :tag]` reads the right action and
  then removes `s` — all of a Choreo spec's state — as the path's root. Replayed
  through driver-map handlers that did nothing at all, the recorded
  `commitTest` trace passed all eight steps. With `:nondet-path` set as well it
  failed, but only on `Init`'s empty tuple, which this release accepts. Both
  are now `:bad-decode-path`, and the message names `:state-path`.

### Recorded, not worked around

- `quint verify` through Apalache fails on every Choreo spec: a state
  variable whose type has a type parameter fixed only by instantiating the
  module — Choreo's `s` — is rejected by Apalache's type checker, from Quint
  0.28.0 to 0.33.0. Reduced to thirteen lines in
  [notes/itf-format.md](docs/notes/itf-format.md) §Choreo. `{:backend :tlc}`
  checks Choreo specs instead, and writes no trace to replay.

## [0.6.1] — 2026-09-09

### Fixed

- **A `halt` that throws no longer replaces the answer the run came for.** It
  ran in a `finally`, and on the JVM an exception from a `finally` discards
  whatever was already on its way out — the diverging step, the handler's
  exception, a broken-setup `ex-info`. A failing cleanup could therefore hide
  the divergence the trace existed to find. Now: if the replay produced a
  result, the failure is `:halt-failed` and carries that result under
  `:result`; if the replay itself threw, that exception is the one raised and
  the halt's is attached to it as a suppressed exception. Nothing is lost in
  either direction.

- **A corrupted tag payload is `:bad-itf`, not a JVM exception or a wrong
  value.** The four ITF tags were decoded without checking the shape of what
  they carried. `{"#bigint": 7}` reached the caller as a `ClassCastException`
  and `{"#set": 7}` as an `IllegalArgumentException`; worse, `{"#tup": "x"}`
  decoded to `[\x]` and `{"#map": [[1]]}` to `{1 nil}` — quietly wrong values
  that only surfaced as a divergence several steps into a replay. The shape is
  now checked before anything decodes it, and the message names the tag and
  what ITF writes there. A bignumber whose `s`/`e`/`c` fields hold numbers
  rather than strings stops matching the bignumber shape and fails the same
  way.

- **A corrupted state `#meta` is `:bad-itf` too.** `#meta` was read with a
  plain `get`, so a `#meta` that was not an object, or an `index` that was not
  an integer, gave `:index` nil — which surfaced far downstream as a failure
  report about step nil. Both are now refused where they are read. A `#meta`
  carrying no index at all is still accepted: ITF lets a producer put what it
  likes there, and no recording of one without an index exists to hold that
  against.

The first two were found in third-party review of 0.6.0, the third while
fixing them.

## [0.6.0] — 2026-08-26

### Fixed

- **Cross-file imports do work**, and `notes/itf-format.md` said they did not.
  The form is `import lib.* from "./lib"` — the path without the extension. The
  note called the "sibling modules resolve" rationale in `quint.clj` untestable
  because of it; it is testable, and true. Corrected by running it.

- **The Choreo entry in the roadmap was inference and is now a recording.**
  Choreo's own `two_phase_commit.qnt` run under `--mbt` says
  `mbt::actionTaken` is `"step"` on every step, that the picks carry both the
  acting process and the whole chosen transition, and that all state is one
  variable. A Choreo spec can therefore drive an implementation through a
  single driver-map `:actions` entry — which the entry previously said it
  could not.

### Added

- `bb release <version>` — bumps `build.clj`, opens a dated CHANGELOG section
  and rewrites the six files that repeat the coordinate, in one step. Refuses a
  version already in `build.clj`, and an `[Unreleased]` section with nothing
  under it. It stops at the edits and prints the commit, tag, push and deploy
  commands rather than running them: publishing stays a human step, and the
  reason is in [0008](docs/decisions/0008-release.md).

  It rewrites the coordinate specifically, not every occurrence of the old
  version: `io.github.cognitect-labs/test-runner` is pinned by a git tag that
  has already collided with one of ours, and a blanket replace turned it into a
  tag that does not exist. Found by using the task to cut the release it
  shipped in. A coordinate that stops matching aborts the release rather than
  bumping half of it.

- **A clj-kondo config ships in the jar.** `defdriver` is `(def name (driver
  m))`, which clj-kondo cannot know without expanding the macro, so every
  driver name read as an unresolved symbol in the consumer's own tests:

  ```
  test/myapp/model_test.clj:7:14: error: Unresolved symbol: counter
  ```

  clj-kondo reads exported configs off the classpath, so this needs no setup
  beyond the `--copy-configs` a consumer already runs. Linting the `:quint/*`
  annotations is deliberately not in it — that would mean hooking
  `clojure.core/defn` for every `defn` in a consumer's project. See
  [roadmap](docs/roadmap.md) §M8.

- `:backend` in the driver map, passed to `--backend`. It names different
  things per command — the evaluator for `check` and `check-run`
  (`:typescript` or `:rust`), the model checker for `verify` (`:apalache` or
  `:tlc`) — and the value is passed through rather than validated against a
  copy of an enum that is Quint's to change.

  Worth having because the default evaluator has quirks this repo already
  recorded: integers of absolute value `>= 10^15` come out as bignumber.js
  internals, and a large negative literal fails at runtime. Until now there was
  no way around either from the driver map.

- `:bad-options` — `:actions` or `:state` passed to `check`, `check-run` or
  `verify` in the options rather than the driver map. They shape the driver,
  and the driver has already been resolved: merged in there they replaced the
  resolved maps with raw functions and dispatch died at step 0 with nothing
  pointing back at the option that did it.

### Fixed

- **Deleting the scratch directory followed symbolic links out of it.**
  `file-seq` descends them, and Apalache runs in that directory. Verified both
  ways against a link pointing at a directory outside: the old shape emptied
  it, the new one does not. Deletion also says so on stderr now if anything
  survives, rather than discarding every `.delete` result — it runs in a
  `finally` and must not replace the result on its way out, so it warns instead
  of throwing.
- **`save-failure!`'s `:name` accepted a path.** A separator in it wrote
  outside the directory `:dir` named. Now `:save-failed`, pointing at `:dir` as
  the option that does mean that.
- **`:quint/args` was not checked against the parameter list.** Too many or too
  few entries reached the handler as an `ArityException` at some step of some
  trace, with the annotation nowhere in the message. Now `:bad-args` at driver
  construction. Skipped for multi-arity and variadic handlers, where the count
  says nothing.

## [0.5.1] — 2026-08-21

### Fixed

- **`quint/exec` could deadlock.** Both of a subprocess's streams are pipes,
  and a pipe that fills blocks the writer, so draining stdout to EOF and only
  then reading stderr hangs whenever stderr fills first. Reproduced against a
  child writing 270 KB to stderr: the old shape never returned, the new one
  returns immediately. Not reachable through `quint` at `--verbosity=0` — the
  most a broken spec produced was 337 bytes — so this was latent rather than
  live, and the fix is one `future` that drains stderr while stdout is read.

- **`mbt::actionTaken` is the first *named* action, not the outermost one**, and
  [notes/itf-format.md](docs/notes/itf-format.md) said otherwise. It claimed
  `any { all { a, b } }` yields `""`; it yields `"a"`. A named `step` records
  `"step"`. The empty string appears only when the branch that fired is built
  from bare assignments with no named action inside it. Corrected by recording,
  and `anonymous.qnt` / `anonymous_0.itf.json` now carry both cases in one
  trace — which also closes the one failure mode in the taxonomy that had no
  fixture and no test, against CONTRIBUTING's own rule.

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
