# Roadmap

Each milestone is one reviewable change set: small, self-contained, with its own
tests, and useful on its own. Nothing starts before the previous milestone's
acceptance criteria are met.

---

## M0 — Skeleton (done)

Project layout, `deps.edn`, docs, ADRs, ITF fixtures recorded from Quint 0.32.0,
metadata mechanics verified against Clojure 1.12.5.
No library code.

---

## M1 — `uno.michelada.quint-connect.itf`: decode (done)

Pure ITF JSON -> EDN.

- Decode `#bigint`, `#map`, `#set`, `#tup`, records and sum types
  (`{tag, value}`). `#unserializable` is deferred to M7: Quint's writer never
  emits it, only its reader accepts it, so no recording exists to test against.
- Unwrap `Some`/`None` **only** inside `mbt::nondetPicks`, where Quint's own
  runtime puts it regardless of what the spec declares; `None` drops the key.
  Those tags in an ordinary state variable are a type the spec declared itself
  and decode like any other variant.
- Decode the bignumber.js form Quint leaks for integers `>= 10^15`
  (`{s, e, c}`); the reconstruction rule is in
  [notes/itf-format.md](notes/itf-format.md).
- Normalize variable names: `bankTest::bank::balances` -> `:balances`; error on
  collision; `:key-fn` option to override. Names are **not** kebab-cased —
  Quint is camelCase and round-tripping matters more than Clojure aesthetics.
- Split `mbt::actionTaken` / `mbt::nondetPicks` out of the state into
  `:action` / `:picks`.

**Done when:** every `dev/fixtures/*.itf.json` decodes to the expected EDN —
`collide_0` excepted, which is recorded precisely to fail without a `:key-fn` —
the two bigint fixtures decode *identically*, and `#meta` noise (timestamp,
description) is ignored.

---

## M2 — `uno.michelada.quint-connect.replay` + `uno.michelada.quint-connect.report`: the loop (done)

No subprocess, no file I/O, no metadata — this milestone consumes a resolved
driver map that the tests build by hand. The only effects are the ones the
driver's own functions perform.

- `(replay/run-trace driver trace)` -> result data as specified in
  architecture.md §6, including the coverage report.
- The loop: `init`, read state, compare to state 0; then for each following
  state, call the handler with its picks, read state, compare; `halt` in a
  `finally`. Stop at the first mismatch.
- State is read through the driver's readers on every step. Handler return
  values are discarded.
- Unknown action, throwing handler, failing reader: structured results or typed
  `ex-info`.
- `report/failure-str` renders step index, action, picks, handler var, reader
  vars, and a readable diff.

**Done when:** a hand-written driver over an atom passes against the committed
bank fixture; a deliberately broken one fails at exactly the expected step with
a diff a human can act on; an `init` that forgets to reset fails at step 0 of
the second trace.

---

## M3 — `uno.michelada.quint-connect.registry`: the annotations (done)

The reflective layer, and the reason the project has the shape it has. It turns
`:quint/*` metadata into the driver map M2 already consumes.

- `require` each namespace in `:scan`, read `ns-interns`, collect annotations
  from the var and, for `IReference` values, from the object too.
- Read the qualifier from the driver's `:key-ns` (a symbol, default `quint`),
  in one place. All five keys move together; there is no per-key override.
- `:quint/action` -> handler, picks bound positionally from `:arglists`;
  `:quint/args` overrides; multi-arity without it is `:ambiguous-arity`.
- `:quint/state` on a function var (called with no arguments) and on an
  `IDeref` var (dereferenced), then `:path` via `get-in`; `:*` merges a whole
  map of spec variables.
- `:quint/init` / `:quint/halt` -> the per-trace lifecycle.
- Merge the driver map over the scan result; the map wins.
- Validate loudly: `:empty-scan` (naming the `^{...} (defn ...)` trap),
  `:duplicate-action`, `:duplicate-state`, `:ambiguous-arity`,
  `:missing-state`, `:no-init`.
- Rebuild on every call. No global registry atom, ever.

**Done when:** a fixture namespace with every annotation form resolves to the
exact driver map M2 was tested against; each validation error has a test; the
metadata trap produces a message that names it.

---

## M4 — `uno.michelada.quint-connect.quint`: talk to the CLI (done)

- Build the command for `run` (`--mbt --seed --n-traces --max-samples
  --max-steps --out-itf --verbosity 0`), run it in a temp dir with
  `clojure.java.process`, and read back the files.
- Note: `--max-samples` is *attempts*, `--n-traces` is *traces written*. Expose
  both; do not silently equate them the way quint-connect does.
- Random seed generation, and print the reproduce line on failure.
- Check `quint --version` once, warn on drift from the tested version.

**Done when:** traces are generated from `dev/fixtures/bank.qnt`, temp dirs are
cleaned, and a missing binary / broken spec / zero traces each produce a clear
typed error carrying Quint's own stderr verbatim.

---

## M5 — `uno.michelada.quint-connect.core` + `uno.michelada.quint-connect.test`: usable (done)

- `q/driver`, `q/defdriver`, `q/check`, `q/replay-file`, plus the `clojure.test`
  bridge that turns a result map into one assertion with a good message.
- A real toy bank implementation in `dev/`, annotated, with a real `deftest`.

**Done when:** `bb test` runs a genuine model-based test end to end; injecting a
bug into the toy implementation produces a failure naming the action, the
handler var, the step, the diff, and the seed to reproduce.

---

## M6 — Failure artifacts

Turn a failure into a permanent regression test.

- Write the failing trace to `test-resources/quint-connect/failures/`.
- `q/replay-file` reads it back with no Quint installed.
- Document the workflow: random failure -> committed trace -> deterministic test.

**Done when:** the documented loop works from a cold checkout without Quint.

---

## M7 — `verify` and scripted runs

- `q/verify` — run Apalache through `quint verify`, treat "invariant holds" as a
  pass, and replay the counterexample against the implementation when it does
  not.
- `q/check-run` — traces from a named Quint `run` via `quint test`.
- `:action-path` / `:nondet-path`: read the action name and picks from ordinary
  spec variables, since neither `quint test` nor `quint verify` emits `mbt::`
  variables (verified on 0.32.0). This is also what
  [Choreo](https://github.com/informalsystems/choreo/)-style specs need.

**Done when:** a spec with a deliberate invariant violation produces a
counterexample that replays against the implementation, and a spec that tracks
its own `actionTaken` variable drives the driver.

---

## M8 — Ergonomics, only after the above is boring

Candidates, in rough priority order. Each needs its own justification when its
turn comes; none is committed to now.

- `uno.michelada.quint-connect.cli` — generate and cache traces into `test-resources/`, so CI can
  run without Quint.
- Richer annotations, *only* where a real spec demanded them: `:quint/action`
  with a set of names, per-var `:quint/compare`, `:quint/ignore`.
- Variant handling configurable per driver, or per variable: a `:variants`
  option on the decoder, additive to the existing `:key-fn`. Not built now —
  leaving variants wrapped is the reversible choice, and a state reader or
  `:compare` already covers it without new machinery.
- Kaocha plugin: seed reporting, per-trace progress, `--focus` on a trace file.
  Roughly 100 lines against `kaocha.hierarchy`. Optional, separate artifact with
  its own `deps.edn`.
- `clj-kondo` hooks so annotated vars and unknown `:quint/*` keys are linted.
- Shrinking. Probably never: the first diverging step is already the minimal
  information, and dropping steps from a state machine trace produces traces the
  spec never generated.
- `witnesses` / `--invariants` support for targeted trace generation.
