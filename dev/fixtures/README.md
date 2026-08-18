# Fixtures

Recorded output of Quint 0.32.0, used as test data. Regenerate with
`bb fixtures` (only rewrites the `bank_run_*` files; the others are produced by
the commands in [../../docs/notes/itf-format.md](../../docs/notes/itf-format.md)).

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
| `collide.qnt`, `collide_0.itf.json`         | one module instantiated twice, so two variables end in `::n` — decodes only with a `:key-fn` |

The `#meta.timestamp` and `#meta.description` fields differ on every
regeneration. Decoding must ignore them.

`collide_0.itf.json` was recorded with:

```
quint run collide.qnt --max-steps=2 --n-traces=1 \
      --out-itf=collide_{seq}.itf.json --verbosity=0
```

It is the one fixture that is *supposed* to fail: with the default name
normalization both variables become `:n`, which is `:name-collision`.
