# Upstream: `quint verify --backend=tlc` should write `--out-itf`

A proposal for a contribution to [Quint](https://github.com/informalsystems/quint),
written so that it can be picked up in another environment — one with access to
Quint's issues and pull requests, which the one that wrote this did not have.
Everything below was recorded against **Quint 0.33.0** (evaluator 0.7.0,
Apalache 0.62.1) on 2026-09-30, unless it says otherwise. Paths and line
numbers are at tag `v0.33.0`.

## Why it matters here

`q/verify` replays a counterexample against the implementation — the one part
of `verify` that tests the code rather than the spec. Under Apalache that
works. Under TLC it cannot, because Quint writes no trace: `--out-itf` is
honoured only on the Apalache path. And TLC is the only checker that accepts a
Choreo spec (Apalache rejects every one; see
[../notes/itf-format.md](../notes/itf-format.md) §Choreo) and the only one
that checks Quint 0.33.0's action properties.

Once Quint writes the file, **this library needs no change**: `quint/verify!`
already treats "exit 1 with a trace" as a counterexample and replays it.

## What happens today

1. `verifySpec` in `quint/src/cliCommands.ts` sends `--backend=tlc` to
   `verifyWithTlcBackend` in `quint/src/verify.ts` (lines 37–95).
2. That flattens the main module, has Apalache **compile** it to TLA+
   (`compileToTlaplus`), and calls `runTlc` — `verify` in `quint/src/tlc.ts`.
3. `tlc.ts` writes `<main>.tla` and a `.cfg` (`INIT q_init`, `NEXT q_step`,
   `INVARIANT q_inv` and/or `PROPERTY q_temporalProps`) into a
   `mkdtemp` directory, and spawns
   `java -cp <apalache>/lib/apalache.jar tlc2.TLC -deadlock -workers N -metadir <tmp> <main>.tla`.
   TLC ships inside Apalache's jar; there is no separate download.
4. TLC's stdout is forwarded only at high verbosity. On `close` the temp
   directory is deleted, and the exit code alone decides the result
   (`TLC_EXIT_VIOLATION_MIN..MAX` = 10–14 → `tlcErr('found a counterexample', true)`).
5. `processTlcResult` in `quint/src/cliReporting.ts` (line 134) reports it.
   Nothing reads `args.outItf`.

Compare the Apalache path: `processApalacheResult` in the same file receives
the counterexample *as ITF from Apalache* (`err.traces[0]`) and calls
`writeToJson(stage.args.outItf, err.traces[0])`. Apalache knows Quint's types;
TLC does not, which is the whole difficulty.

TLC does find and print the trace. At `--verbosity=5`:

```
Error: Action property coordinatorNeverMoves is violated.
Error: The behavior up to this point is:
State 1: <Initial predicate>
two_phase_commit_tracked_choreo_s = [messages |-> [c |-> {}, ...], ...]
State 2: <two_phase_commit_tracked_choreo_process_transitions line 619, ...>
...
Trace exploration spec path: /tmp/quint-tlc-.../two_phase_commit_tracked_TTrace_1790807176.tla
```

— and then the directory holding that `_TTrace_` file is removed.

## What TLC can export

`tlc2.TLC` in Apalache 0.62.1's jar accepts **`-dumpTrace json <file>`**: "in
case of a property violation, formats the TLA+ error trace as the given format
and dumps the output to the specified file." Recorded by running Quint's
pipeline by hand (reproduction below) on a spec with one variable of every
kind:

```json
{"vars": ["aSet", "aTup", ...],
 "counterexample": {
   "state":  [[1, {"aSet": [1, 2, 3], ...}], [2, {...}]],
   "action": [[[1, {...}], {"name": "step", "location": {...}}, [2, {...}]]]}}
```

How each Quint value comes out:

| Quint value                         | TLC's JSON                              |
| ----------------------------------- | --------------------------------------- |
| `Set(3, 1, 2)`                      | `[1, 2, 3]` — an array, sorted          |
| `(7, "seven")`                      | `[7, "seven"]` — an array               |
| `["x", "y"]` (List)                 | `["x", "y"]` — an array                 |
| `{ x: 1, y: "why" }`                | `{"x": 1, "y": "why"}` — an object      |
| `Map("a" -> 1)`                     | `{"a": 1}` — an object                  |
| `Map(1 -> Set("a"), 2 -> Set())`    | **`[["a"], []]`** — an array: TLC treats a function on `1..n` as a sequence |
| `Map(5 -> Set("a"), 10 -> Set())`   | `{"5": ["a"], "10": []}` — an object, keys as strings |
| `Named({ who: "bob", n: 3 })`       | `{"Named": {"who": "bob", "n": 3}}`     |
| `Busy(2)`                           | `{"Busy": 2}`                           |
| `Idle` (no argument)                | `{"Idle": {"tag": "UNIT"}}`             |
| `true`, `0`, `"why"`                | `true`, `0`, `"why"`                    |
| an integer `>= 2^31`                | TLC refuses the spec: `TLC can't handle a number this big.`, exit 255 |

Variable names are Apalache's flattened ones: `two_phase_commit_tracked::choreo::s`
becomes `two_phase_commit_tracked_choreo_s`.

**The consequence: the JSON cannot be converted without the types.** An array
is a set, a tuple, a list or an int-keyed map; an object is a record, a
string- or int-keyed map, or a variant. Quint has every variable's type after
type checking, so the conversion is type-directed, and it belongs in Quint.

## The change, as a plan

1. **Ask first.** Search Quint's issues and discussions for `tlc` + `itf`,
   `out-itf`, `dumpTrace`, `counterexample`. If nothing exists, open an issue
   with the evidence above and the proposed design, and ask whether the
   maintainers want it and whether they have a preference between
   `-dumpTrace json` and parsing the `_TTrace_` module. Then the PR.
2. **Ask TLC for the trace.** In `tlc.ts`, when a trace is wanted, add
   `-dumpTrace json <tmp>/trace.json` to the spawn arguments, and read the file
   in the `close` handler *before* `fs.rmSync(tmpDir, ...)`. Carry the parsed
   JSON out on `TlcError` (say `trace?: unknown`), the way the Apalache path
   carries `traces`.
3. **Convert, by type.** A new function — `tlcJsonToQuint(value, type)` or so,
   likely in its own file with its own tests — turning one JSON value into a
   `QuintEx`, walking the variable's Quint type:
   - `set` ← array; `list` ← array; `tup` ← array, element by element;
   - `rec` ← object, field by field;
   - `fun` (map) ← object (keys parsed by the key type: `"5"` → `5`) **or**
     array (an int-keyed map on `1..n`: index `i` is key `i + 1`);
   - `sum` ← an object with one key, the tag; `{"tag": "UNIT"}` is the empty
     tuple;
   - `int`, `bool`, `str` ← as they are.
   Where the types come from: the compiled stage has the lookup table and the
   types of the flattened module (`prev.table`, and the var declarations'
   types); the Apalache path's `varTypes` in ITF `#meta` shows the types are
   available. Confirm the exact fields in `CompiledStage` before writing this.
4. **Map names back.** Build the table from Quint's own variable list — replace
   `::` with `_` for each, and invert. Never split on `_`: module and variable
   names contain underscores (`two_phase_commit_tracked`). Refuse if two names
   collide after mangling.
5. **Write it.** One state `QuintEx` per `counterexample.state` entry, in index
   order, then the existing `toItf(vars, states)` (`quint/src/itf.ts:110`),
   `addItfHeader` and `writeToJson(args.outItf, ...)` — exactly what `run` and
   `test` already do through `prepareOnTrace`.
6. **Tests.** Unit tests for the converter, one per row of the table above
   (the int-keyed map on `1..n` and the unit variant are the ones that will
   break). An integration test in Quint's CLI tests: `quint verify
   --backend=tlc --invariant=... --out-itf=x.itf.json` on a small spec, and the
   file equal to what `--backend=apalache` writes for the same counterexample
   — the same trace from both checkers is the strongest check there is.
7. **CHANGELOG** under Unreleased/Added.

## Open questions to settle with the maintainers

- **Liveness.** A violated liveness property's counterexample is a lasso — a
  prefix and a loop back to an earlier state — and ITF (ADR-015) has a field
  for where the loop starts. Neither TLC's output for one nor that field was
  recorded here; check both. Action properties, being safety properties,
  produced plain finite traces in everything recorded above. Decide whether
  the first PR handles loops or refuses to write them.
- **`-dumpTrace json` versus the `_TTrace_` module**, which TLC also writes.
  The JSON needs no TLA+ parsing, which is why it is proposed here.
- **Which TLC.** `-dumpTrace` exists in the TLC inside Apalache 0.62.1. Check
  it in whatever Apalache version Quint pins when this is done.
- **`mbt::` metadata.** TLC knows the action a step took by its TLA+ name and
  location (`counterexample.action[i][1].name`), not by Quint's name. Mapping
  it back is possible but not needed for this; a spec that records its own
  action, as quint-connect-clj asks, works without it.

## Reproducing the evidence

```bash
# the TLA+ Quint hands TLC, with Apalache's two banner lines removed
quint compile --target=tlaplus --invariant=small enc.qnt > enc.tla
sed -i '1,/^-* MODULE/{/^-* MODULE/!d}' enc.tla
printf 'INIT q_init\nNEXT q_step\nINVARIANT q_inv\n' > enc.cfg

java -cp ~/.quint/apalache-dist-0.62.1/apalache/lib/apalache.jar tlc2.TLC \
     -deadlock -dumpTrace json trace.json -metadir meta enc.tla
```

`enc.qnt` is any spec with an invariant violated in a few steps; the one used
for the table declared one variable of each kind above and `val small =
counter < 1` over a counter incremented by `step`. For a temporal property,
compile with `--temporal=<name>` and use `PROPERTY q_temporalProps` in the
`.cfg`. `quint verify` downloads Apalache on first use; the jar path follows
its version.

## When it lands, in this repository

- Record a TLC counterexample of `dev/fixtures/choreo/two_phase_commit_tracked.qnt`
  (`--invariant=wit_commit` or `--temporal=coordinatorNeverMoves`) as a fixture,
  and replay it in `choreo_test` with no Quint.
- The `:quint-failed` "under TLC a violated property looks the same" message in
  `quint/verify!` becomes the old-Quint path only; raise the floor in
  [0014](../decisions/0014-quint-floor.md) or keep the message for older
  versions — decide then.
- `docs/choreo.md`, the example README and the CHANGELOG stop saying TLC has
  nothing to replay.
