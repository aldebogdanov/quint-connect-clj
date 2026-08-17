# Traces

Two places, on purpose.

`failures/` is where `test/save-failure!` drops the
trace of a divergence, every time one happens. It is **gitignored**: a failing
run must not dirty the repository on its own.

This directory is where a trace lands once a human decides it is worth keeping.
Promotion is a `mv`, and it is the whole ceremony:

```
mv test-resources/quint-connect/failures/bank-seed42-trace0.itf.json \
   test-resources/quint-connect/
```

From there `q/replay-file` (or `qt/replay-file`, which asserts) turns it into a
deterministic regression test that needs no Quint. The name Quint-side is
`<spec>-seed<seed>-trace<index>.itf.json`, and it is deterministic — re-running
a failing seed rewrites one file instead of leaving near-copies.

| file | what found it |
| ---- | ------------- |
| `bank-seed42-trace0.itf.json` | an off-by-one injected into `bank.core/deposit`; diverges at step 6, action `deposit` |

The workflow is written out in
[../../docs/getting-started.md](../../docs/getting-started.md) §6.
