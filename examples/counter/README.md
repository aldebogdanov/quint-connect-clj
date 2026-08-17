# Example: the getting-started counter

This is [docs/getting-started.md](../../docs/getting-started.md) §1–§5, as a
project you can run instead of a tutorial you have to retype.

```
clojure -M:test
```

```
Ran 1 tests containing 1 assertions.
0 failures, 0 errors.
```

That ran ten randomly generated traces against `src/myapp/core.clj`, comparing
every variable after every step.

## Why it exists

A tutorial nobody executes is a tutorial that rots. The spec and the
implementation here are the same text the document shows, so if one of them
stops working the other is wrong too, and running this is how anyone finds out.

## Seeing it fail

Make `refuse` change the count, which is the bug the document walks through:

```clojure
(defn refuse {:quint/action "refuse"} [n]
  (swap! counter + n)              ; refusing should change nothing
  (reset! last-op "refused"))
```

```
spec and implementation diverged on trace 0 of 10, seed 682320355
diverged at step 1, action "refuse"
  picks    {:n 8}
  handler  #'myapp.core/refuse
  readers  {:count #'myapp.core/counter}
  expected {:count 0}
  actual   {:count 8}
  saved      test-resources/quint-connect/failures/counter-seed682320355-trace0.itf.json
```

The seed and the pick differ every run — that is the point of generating traces
rather than writing them. The `saved` line is what makes one of them permanent;
[getting-started.md](../../docs/getting-started.md) §6 is the workflow.

## What to look at

- [spec/counter.qnt](spec/counter.qnt) — the specification.
- [src/myapp/core.clj](src/myapp/core.clj) — the implementation. It has **no
  `:require` of quint-connect**; the `:quint/*` keys are inert metadata.
- [test/myapp/model_test.clj](test/myapp/model_test.clj) — the whole test.

For a bug that only appears after a particular interleaving, rather than on
step 1, see [../lru/](../lru/).
