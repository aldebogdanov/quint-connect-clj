# Example: a token bucket, sampled and proved

A rate limiter holding five tokens. `grant` hands them out, `refill` puts them
back, and the cap on refilling is the one line that is easy to get wrong.

Needs `quint` on `PATH` (`npm i -g @informalsystems/quint`). The two `verify`
tests additionally need Apalache, which `quint` downloads on first use — a
couple of minutes, once. To skip them:

```bash
clojure -M:test            # everything
clojure -M:test -e :slow   # without Apalache
```

This is the example for **`verify`**, and for the difference between the two
questions this library can ask.

## Two questions, two answers

`check` samples. It generates fifty random traces and compares your code to the
spec at every step. It finds bugs *in your implementation*, and it finds them
only if a trace happens to reach them.

`verify` proves. Apalache checks every reachable state within `:max-steps` and
answers a question about *the specification itself* — is this property true of
the design at all? Your code is only consulted when the answer is no, because
only then is there a counterexample to replay.

That distinction is worth internalising before you reach for it, and this
example is built to make it concrete.

## Break the implementation

Drop the `min` in [src/bucket/core.clj](src/bucket/core.clj), which is how this
bug arrives in real code:

```clojure
(defn refill {:quint/action "refill"} [n]
  (swap! tokens + n))          ; forgot the cap
```

`check` catches it:

```
spec and implementation diverged on trace 0 of 50, seed 1608093364
diverged at step 3, action "refill"
  picks    {:n 4}
  handler  #'bucket.core/refill
  readers  {:tokens #'bucket.core/tokens}
  expected {:tokens 5}
  actual   {:tokens 7}
```

**`verify` does not.** `neverOverCapacity` is still true of the spec — the spec
caps correctly — so Apalache reports no violation, produces no counterexample,
and your broken code is never run. A green `verify` says the design is sound,
not that you implemented it.

## Break the specification

The other direction. `neverStarved` claims the bucket never empties, which is
not true of a bucket you can drain:

```
  val neverStarved = tokens > 0
```

Apalache finds the trace that disproves it in one step, and quint-connect
replays that trace against your implementation. The result is `:ok? false` with
`:failure` **nil** — the counterexample ran cleanly, your code agrees with the
spec, and the bug is in what you asked for rather than in what you wrote. The
message says exactly that:

```
invariant "neverStarved" does not hold; the implementation reproduces it
faithfully, so the spec is where to look
```

The counterexample is saved as
`tokens-neverStarved-counterexample.itf.json` — named after the invariant, not
a seed, because Apalache rolls no dice and re-checking rewrites one file.

## Three outcomes, in one table

| what `verify` returns | means |
| --- | --- |
| `:ok? true` | the property holds of the design; your code was not consulted |
| `:ok? false`, `:failure` nil | the property is false of the design; your code matches the counterexample |
| `:ok? false`, `:failure` set | the property is false *and* your code diverges from the counterexample too |

## Why the spec tracks its own action

`quint verify` emits no `mbt::` variables, so nothing in a counterexample says
which action was taken. The spec records it in `lastAction` and `lastPick`, and
the driver reads them with `:action-path` / `:nondet-path`. Those two variables
are split out of the compared state, so `bucket.core` is never asked to supply
the spec's bookkeeping — it only knows about `tokens`.

The same driver serves `check`, which does not need any of it.

## Cost

`verify` downloads Apalache on first use, and a search is seconds here but can
be minutes on a real spec. Keep those tests tagged and out of the suite you run
on every save — they are `^:slow` here.

## What to look at

- [spec/tokens.qnt](spec/tokens.qnt) — the specification and its three invariants.
- [src/bucket/core.clj](src/bucket/core.clj) — no `:require` of quint-connect.
- [test/bucket/model_test.clj](test/bucket/model_test.clj) — one `check` and
  two `verify`s.

For sampling on its own, see [../lru/](../lru/); for the basics, start at
[../counter/](../counter/).
