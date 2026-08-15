# What Quint actually emits

Observed with **Quint 0.32.0** on 2026-08-13. Everything here was produced by
running the CLI, not read from documentation. The files in
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

- `mbt::actionTaken` — the name of the action taken to reach this state.
  `"init"` in state 0. Empty string when the action was anonymous.
- `mbt::nondetPicks` — a record of every `nondet` binding in the `step` action,
  each wrapped in `Some`/`None`. Bindings not used on the branch that fired are
  `None`. In `shapes.qnt`, whose `step` has no `nondet`, it is `{}`.

An anonymous action (`any { all { a, b } }`) yields `""` and cannot be
dispatched. The fix belongs in the spec: name the combined action. quint-connect
must say so in the error message.

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

## Large integers: a real trap

On the **default rust backend**, integers with absolute value `>= 10^15` are
serialized as bignumber.js internals instead of `#bigint`:

```json
"unsafe": {"s": {"#bigint": "1"},
           "e": {"#bigint": "15"},
           "c": [{"#bigint": "90"}, {"#bigint": "7199254740993"}]}
```

That is `9007199254740993`. The threshold is exact: `999999999999999` encodes
correctly, `1000000000000000` does not. The `typescript` backend encodes both
correctly as `{"#bigint": "..."}`. Fixtures: `bigint_rust_0.itf.json`,
`bigint_typescript_0.itf.json`.

Reconstruction rule, verified against all three fixture values:

```
digits = c[0] ++ (each later chunk left-padded with zeros to 14 characters)
value  = s * digits * 10^(e + 1 - (count digits))
```

Also on the rust backend, a large *negative* literal (`-12345678901234567890`)
fails at runtime with `error: Runtime error`; the typescript backend evaluates
it fine.

Decisions for M1: decode the `{s, e, c}` form as well as `#bigint`, add these
fixtures to the suite, and report the quirk upstream. Do not silently switch
users to the typescript backend — it is much slower — but mention it in the
error message if a decode fails on this shape.
