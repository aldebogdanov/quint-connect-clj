# Fixtures

Recorded output of Quint 0.32.0, used as test data. Every one was re-recorded
on 0.33.0 on 2026-09-30 and came out identical but for `#meta` timestamps,
except `choreo/tpc_mislabel_0.itf.json`, which records the bug 0.33.0 fixed —
see [0014](../../docs/decisions/0014-quint-floor.md). Regenerate with
`bb fixtures` (rewrites the recordings made with a fixed seed or a named test,
`choreo/` included; the others are produced by the commands in
[../../docs/notes/itf-format.md](../../docs/notes/itf-format.md)).

| file                                        | what it is                                               |
| ------------------------------------------- | -------------------------------------------------------- |
| `bank.qnt`                                  | toy spec: `bank`, `bankTest` (simulation), `bankRuns` (scripted run) |
| `bank_run_0/1.itf.json`                     | `quint run --mbt`, seed 42 — the normal case              |
| `bank_test_depositThenWithdrawTest.itf.json`| `quint test` — note the absent `mbt::` variables          |
| `tracked.qnt`                               | the same bank, recording its own action in `lastAction` and its picks in `lastPick`; `trackedTest` (simulation), `trackedRuns` (scripted run), `trackedBroken` (a run whose expectation fails) |
| `tracked_test_depositThenOverdraftTest.itf.json` | `quint test` — no `mbt::`, so `:action-path` is the only way it drives anything |
| `tracked_run_0.itf.json`                    | `quint run --mbt` of the same spec — carries both mechanisms, which is what proves they agree |
| `shapes.qnt`, `shapes_0.itf.json`           | one instance of every ITF value encoding, lists included  |
| `bigint.qnt`, `bigint_rust_0.itf.json`      | large-integer serialization quirk under `--backend=rust`  |
| `bigint_typescript_0.itf.json`              | the same spec, encoded correctly by the typescript backend |
| `anonymous.qnt`, `anonymous_0.itf.json`     | a step with one branch that has no named action in it, so `mbt::actionTaken` is `""` — the case a driver cannot dispatch |
| `collide.qnt`, `collide_0.itf.json`         | one module instantiated twice, so two variables end in `::n` — decodes only with a `:key-fn` |
| `choreo/`                                   | vendored [Choreo](https://github.com/informalsystems/choreo) (Apache-2.0), its two-phase commit as written and instrumented, and three recordings of them — see [choreo/README.md](choreo/README.md) |

The `#meta.timestamp` and `#meta.description` fields differ on every
regeneration. Decoding must ignore them.

`collide_0.itf.json` was recorded with:

```
quint run collide.qnt --max-steps=2 --n-traces=1 \
      --out-itf=collide_{seq}.itf.json --verbosity=0
```

It is the one fixture that is *supposed* to fail: with the default name
normalization both variables become `:n`, which is `:name-collision`.
