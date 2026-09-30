# What Quint actually emits

Observed with **Quint 0.32.0** on 2026-08-13, and re-checked on **0.33.0** on
2026-09-30: every fixture re-recorded identically but for `#meta` timestamps,
except the state-0 bug 0.33.0 fixed, and `verify_probe.sh` reproduced its
findings on Apalache 0.62.1 with the one change noted below. 0.33.0 is the
floor; see [../decisions/0014-quint-floor.md](../decisions/0014-quint-floor.md).
Everything here was produced by running the CLI, not read from documentation. The files in
[`dev/fixtures/`](../../dev/fixtures/) are the recordings; regenerate with
`bb fixtures`.

Format reference: [ITF / ADR-015](https://apalache-mc.org/docs/adr/015adr-trace.html).

## Shape of a trace file

```json
{"#meta": {"format": "ITF", "source": "bank.qnt", "status": "ok",
           "description": "Created by Quint on ...", "timestamp": 1786569203837},
 "vars": ["bankTest::bank::balances", "bankTest::bank::lastError",
          "mbt::actionTaken", "mbt::nondetPicks",
          "mbt::actionTaken", "mbt::nondetPicks"],
 "states": [{"#meta": {"index": 0},
             "bankTest::bank::balances": {"#map": [["alice", {"#bigint": "0"}]]},
             "bankTest::bank::lastError": "",
             "mbt::actionTaken": "init",
             "mbt::nondetPicks": {"amount": {"tag": "None", "value": {"#tup": []}},
                                  "who":    {"tag": "None", "value": {"#tup": []}}}}]}
```

Note the duplicated `mbt::` entries in `vars`. Treat `vars` as informational;
derive the real variable set from the state keys.

## Value encodings

| Quint value      | JSON                                              | decodes to                 |
| ---------------- | ------------------------------------------------- | -------------------------- |
| `int`            | `{"#bigint": "39"}`                                | `39`                       |
| `str`            | `"why"`                                            | `"why"`                    |
| `bool`           | `true`                                             | `true`                     |
| `Set(1,2,3)`     | `{"#set": [{"#bigint": "1"}, ...]}`                | `#{1 2 3}`                 |
| `(7, "seven")`   | `{"#tup": [{"#bigint": "7"}, "seven"]}`            | `[7 "seven"]`              |
| `{x: 1, y: "w"}` | `{"x": {"#bigint": "1"}, "y": "w"}`                | `{:x 1 :y "w"}`            |
| `Map(1 -> ...)`  | `{"#map": [[key, value], ...]}`                    | `{1 ...}`                  |
| `["x", "y"]`     | `["x", "y"]`                                       | `["x" "y"]`                |
| sum type variant | `{"tag": "Busy", "value": {"#bigint": "2"}}`       | design decision, see below |
| `Some(5)`        | `{"tag": "Some", "value": {"#bigint": "5"}}`       | `5`                        |
| `None`           | `{"tag": "None", "value": {"#tup": []}}`           | absent / `nil`             |

A `List` has no tag of its own: it is a bare JSON array, which is the one
encoding that looks like nothing in particular. Fixture: `shapes_0.itf.json`.

`Option` is not built in — a spec must define
`type Option[a] = Some(a) | None` itself (or import `basicSpells`). It is still
worth special-casing in the decoder, because `mbt::nondetPicks` always wraps its
values in it.

Records are plain JSON objects, so a record with a `tag` field is
indistinguishable from a sum-type variant. Sum types are therefore decoded to
`{:tag "Busy" :value 2}` rather than to anything cleverer, and users map them in
a state reader if they want something else. Fixture: `shapes_0.itf.json`.

## MBT metadata

`quint run --mbt` adds two variables to every state:

- `mbt::actionTaken` — the **first named action** the step executed, which is
  not always the one you would name yourself. `"init"` in state 0. Empty string
  only when the branch that fired contains no named action anywhere in it.
- `mbt::nondetPicks` — a record of every `nondet` binding in the `step` action,
  each wrapped in `Some`/`None`. Bindings not used on the branch that fired are
  `None`. In `shapes.qnt`, whose `step` has no `nondet`, it is `{}`.

**Corrected 2026-08-21, by recording rather than by reading.** This section
used to say `any { all { a, b } }` yields `""`. It does not: if `a` and `b` are
named actions, that branch records `"a"`. Nor does a named `step` go anonymous
— `action step = all { a, b }` records `"step"`. The empty string appears only
when the branch that fired is built from bare assignments, with no named action
inside it at all:

```
action step = any {
  all { n' = n + 1, touched' = true },   // "" — nothing named in here
  idle,                                  // "idle"
}
```

That still cannot be dispatched, and the fix is still in the spec: name the
combination. Fixture: `anonymous.qnt`, `anonymous_0.itf.json`, which carries
both cases in one trace.

## Verified CLI behaviour

- **`quint test` does not support `--mbt`.** Its traces contain no `mbt::`
  variables at all — see `bank_test_depositThenWithdrawTest.itf.json`. Same for
  `quint verify`. Only `quint run --mbt` carries action metadata. Driving an
  implementation from a scripted `run` or from a counterexample therefore
  requires the spec to track the action in an ordinary variable.
- `--max-samples` is the number of *attempts*; `--n-traces` is the number of
  traces *written*. Different knobs, but not independent: `--max-samples`
  defaults to **1**, and Quint exits 1 with `--n-traces (2) cannot be greater
  than --max-samples (1)` whenever traces exceed samples. So samples has a
  floor, not a fixed relationship — raise it to let Quint discard attempts that
  violate a precondition.
- `--out-itf` accepts an absolute path, so traces can be written to a scratch
  directory while quint runs in the spec's own directory — which is what keeps
  `#meta.source` a bare filename instead of a machine-specific path.
- `--out-itf 'name_{seq}.itf.json'` numbers files from 0. `quint test` uses
  `{test}` for the test name.
- Variables carry their full module path: `bankTest::bank::balances`. The
  importing module name comes first.
- `--verbosity 0` is required to keep stdout clean when scripting.
- Default backend is `rust`; `typescript` is available and behaves differently
  (see below).

### `quint test`, recorded 2026-08-17 on 0.32.0

- `--match` takes a **regex**, so a name must be anchored (`--match=^name$`) or
  `depositTest` also selects `depositTestTwo`.
- A name that matches nothing **exits 0 and writes no file**. Silence is the
  whole signal, which is why `test!` turns an empty result into `:no-traces`.
- A run whose `.expect` does not hold exits **1** with `error: Tests failed` —
  and still writes the trace. That is a bug in the spec, before any
  implementation is involved, so it gets its own `:test-failed`.
- A spec that records its own action needs nothing special from the tool: an
  ordinary `var lastAction: str` and a record `var lastPick: {...}` come out as
  plain state, which `:action-path` and `:nondet-path` then split off. Fixture:
  `tracked_test_depositThenOverdraftTest.itf.json`.
- Variables in a module that imports with `.*` and no instantiation carry **no
  path prefix** at all (`count`, not `runs::counter::count`). The prefix comes
  from instantiation, as in `import bank(ACCOUNTS = ...)`.

### `quint verify`, recorded 2026-08-17 on 0.32.0 with Apalache 0.56.1

Not implemented yet; this is what M7b is designed against. Everything below is
reproducible with [`dev/probes/verify_probe.sh`](../../dev/probes/verify_probe.sh).

**Outcome is not in the exit code.** All four failure modes exit 1, so the
number distinguishes nothing. What does distinguish them is whether a trace was
written:

| outcome              | exit | `--out-itf` | first line of output                        |
| -------------------- | ---- | ----------- | ------------------------------------------- |
| invariant holds      | 0    | no file     | silent at `--verbosity=0`                   |
| counterexample       | 1    | **written** | `error: found a counterexample`             |
| unknown invariant    | 1    | no file     | `error: [QNT404] Name '…' not found`        |
| spec does not typecheck | 1 | no file     | ` Error [QNT000]: Couldn't unify int and str` |
| spec file missing    | 1    | no file     | `error: file … does not exist`              |

So the discriminator is **exit 1 with a trace** = counterexample, **exit 1
without one** = broken setup, and the wording belongs in the error message
rather than in the branch. The alternative — matching `found a counterexample`
— works too, but it is a string in someone else's release notes.

An invariant that holds writes no file at all. Zero traces is therefore the
*pass*, which is why `verify!` cannot reuse the `:no-traces` error that `run!`
and `test!` raise on an empty result.

**`_apalache-out/` follows the working directory, not the spec.** Running from
an unrelated directory with an absolute path to the spec puts it in that
directory and leaves the spec's own alone. That is what lets it be contained:
run `quint verify` in a scratch directory and it is deleted along with it.

It cannot be renamed. `--apalache-config` with `common.out-dir` is ignored —
relative or absolute, and `write-intermediate` with it. With Apalache 0.56.1
a config file containing an unknown key still exited 0, so the file was not
being validated and may not have been forwarded at all; Apalache 0.62.1, which
Quint 0.33.0 fetches, rejects it. The name `_apalache-out` is Apalache's; only the
directory it appears in is ours to choose.

Its contents are logs, not results: `_apalache-out/server/<timestamp>/` holding
`log0.smt`, `detailed.log` and `run.txt`.

**The dialect needs nothing from the decoder.** A recorded counterexample
decodes through `itf` unchanged: `#meta` carries
`format`, `varTypes`, `format-description` and `description`, so `:source`
comes out nil and `varTypes` is ignored along with the rest of `#meta`. Values
are plain `#bigint` and records. **No `#unserializable`** — so it stays
deferred, there is still no recording to decode against, and `itf.clj` does not
have to grow for M7b.

There are no `mbt::` variables, so `:action` decodes nil and replay reports
`:unknown-action` pointing at `:action-path` — the same contract `quint test`
traces already have.

**Other observations.** Apalache 0.56.1 is downloaded on first use (~2 minutes,
once) into `~/.quint/apalache-dist-0.56.1` and runs as a server on port 8822; it
exits with the command and left no orphan JVM. `--out-itf` is documented as
suppressing console output and does not — the counterexample states are still
printed unless `--verbosity=0` is also passed. Cost is spec-dependent and can be
large: 5 s to confirm `nonNegative` on a two-action counter, 114 s to find a
counterexample to `balances.get(a) <= 50` on the toy bank.

**Corrected 2026-08-26.** This said no form of cross-file import would load on
0.32.0, so the "sibling modules resolve" rationale could not be tested. That
was wrong, and the working form is `from "./lib"` — the path without the
extension:

```
module usr {
  import lib.* from "./lib"        // lib.qnt beside it
  ...
}
```

`n` runs 0, 1, 3, 7 under `n' = double(n) + 1`, so the imported definition
resolved. Choreo's own files import each other exactly this way
(`from "../../spells/basicSpells"`), which is what prompted re-checking it. So
running Quint in the spec's own directory does have the effect the comment in
`quint.clj` claims, and that comment is no longer resting on an untested
assumption. If M7b runs
`verify` from a scratch directory, this is the assumption it rests on.

## Choreo, recorded 2026-09-30 on 0.32.0

[Choreo](https://github.com/informalsystems/choreo)'s two-phase commit, vendored
at commit `000cf4e` into [`dev/fixtures/choreo/`](../../dev/fixtures/choreo/),
as written and instrumented. Everything below is reproducible with
[`dev/probes/choreo_probe.sh`](../../dev/probes/choreo_probe.sh), and how the
library uses it is [../choreo.md](../choreo.md).

**One variable.** The whole state is `two_phase_commit::choreo::s`, a record of
`events`, `extensions`, `messages` and `system`. The last three are
`#map`s keyed by node id, so they decode with **string** keys. Local states are
records; roles, stages and messages are sum types, and decode like any other
variant: `{:tag "Working" :value []}` for one without an argument. `extensions`
is `()` in Choreo's own spec — `{"#tup": []}`, which decodes to `[]`.

**`--mbt` names nothing.** `mbt::actionTaken` is `"init"` at state 0 and
`"step"` on every step after it, and `mbt::nondetPicks` is `{v, transition}`:
the node that acted, and `{post_state, effects}`. Fixture: `tpc_run_0.itf.json`.

**An instrumented spec names every step, in every trace.** When each
transition records itself into `s.extensions.actionTaken` — the convention
quint-connect (Rust) uses, and the one `two_phase_commit_tracked.qnt`
follows — the name is state, so `quint test` writes it as well as
`quint run`. `Init` carries the empty tuple. Fixtures:
`tpc_tracked_run_0.itf.json`, `tpc_tracked_test_commitTest.itf.json`.

**Instrumenting changes the traces.** `choreo::step` drops transitions with no
effects and an unchanged post-state. Recording one gives every transition an
effect, so a participant already `Aborted` can take `AbortsAsInstructed` again.
In 150 traces of 20 steps, 74% of steps changed neither `system` nor
`messages`. The recorded seed-42 run spends four of its eight steps that way.

**On 0.32.0, state 0 could say `"step"`.** Quint 0.32.0's default rust
evaluator, writing more than one trace of Choreo's own spec, labels state 0 of
most of them `"step"`, with picks `{v: Some("p1"), transition: None}` — an
attempt that was never taken. The state itself is the initial one. Twenty
traces at seed 42: 19 say `"step"`. `--backend=typescript`: all 20 say
`"init"`. It is not Choreo's: a bank whose step is `any { deposit, withdraw }`
and which runs out of enabled actions does the same, with the label `"step"`
rather than either inner action.

The cause is Quint's own and is written down in the commit that fixed it for
0.33.0 (`5b7a850a`, Quint #2012): a sample that ends because `step` found
nothing enabled leaves the failed attempt's action and picks in storage, and
recording is first-write-wins, so the next sample's `init` cannot overwrite
them. Quint 0.33.0 labels every one `"init"`. That is why it is the floor.
Fixture: `tpc_mislabel_0.itf.json` (`"step"`) and `tpc_mislabel_1.itf.json`
(`"init"`), the two traces of one 0.32.0 run.

**`quint verify` fails on every Choreo spec**, instrumented or not, with Quint
0.32.0 / Apalache 0.56.1 and Quint 0.33.0 / Apalache 0.62.1:

```
error: <unknown>: internal error in type checking: A typed declaration
two_phase_commit::choreo::s was transformed to an untyped expression
two_phase_commit::choreo::s
```

It exits 1 and writes no trace. The message comes from Apalache —
`TypeWatchdogTransformationListener`, in `apalache.jar` — and Choreo's
Tendermint fails the same way. `quint compile --target=tlaplus` of the same
spec succeeds. Whose bug it is was not established.

**Imports resolve relative to the importing file.** `choreo.qnt` imports
`"spells/basicSpells"` and finds it beside itself wherever it is vendored, and
`quint typecheck` of a spec by absolute path from an unrelated directory
succeeds — which is how `verify` runs.

## Large integers: a real trap

With the **default `--backend=rust`**, integers with absolute value `>= 10^15`
come out as bignumber.js internals instead of `#bigint`:

```json
"unsafe": {"s": {"#bigint": "1"},
           "e": {"#bigint": "15"},
           "c": [{"#bigint": "90"}, {"#bigint": "7199254740993"}]}
```

That is `9007199254740993`. The threshold is exact: `999999999999999` encodes
correctly, `1000000000000000` does not. `--backend=typescript` encodes both
correctly as `{"#bigint": "..."}`. Fixtures: `bigint_rust_0.itf.json`,
`bigint_typescript_0.itf.json`. Still so on 0.33.0: both re-record identically.

**A JavaScript library in a Rust backend is not a contradiction, and the
attribution above used to imply it was.** What is recorded here is only which
`--backend` value produces which bytes. The `rust` backend is a separate
binary — `~/.quint/rust-evaluator-v0.6.0/quint_evaluator` — that evaluates the
spec, but `quint` itself is a TypeScript program and the ITF file is written by
it, not by the evaluator. So the leak is on Quint's own side of that boundary,
where integers are bignumber.js objects and the writer evidently special-cases
them to `#bigint` only on the path the typescript backend takes.

That last sentence is **inference**, not a recording: the observed facts are
the table of encodings and the reconstruction rule below. The mechanism was not
read out of Quint's source and should not be repeated as though it were.

That the shape is bignumber.js *is* verified, by reconstruction rather than by
assertion: `c` is an array of base-1e14 limbs, which is that library's internal
layout and reassembles every fixture value exactly.

Reconstruction rule, verified against all three fixture values:

```
digits = c[0] ++ (each later chunk left-padded with zeros to 14 characters)
value  = s * digits * 10^(e + 1 - (count digits))
```

Also with `--backend=rust`, a large *negative* literal
(`-12345678901234567890`) fails at runtime with `error: Runtime error`;
`--backend=typescript` evaluates it fine.

Decisions for M1: decode the `{s, e, c}` form as well as `#bigint`, add these
fixtures to the suite, and report the quirk upstream. Do not silently switch
users to the typescript backend — it is much slower — but mention it in the
error message if a decode fails on this shape.
