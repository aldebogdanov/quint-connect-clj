# Example: an LRU cache

A cache that holds three entries and evicts the least recently used. Small
enough to read in a minute, and wrong in a way that is genuinely easy to ship.

```
clojure -M:test
```

## The demo

The interesting rule is in [spec/lru.qnt](spec/lru.qnt): *reading* a key makes
it the most recently used, not just writing it. Miss that and the cache
eventually evicts the wrong entry.

Break [src/cache/lru.clj](src/cache/lru.clj) the way a person actually would —
make a read not count as a use:

```clojure
(defn lookup {:quint/action "read"} [k]
  (get @entries k))          ; looks right, and is wrong
```

Run `clojure -M:test`:

```
diverged at step 12, action "read"
  picks    {:k "b", :v 9}
  handler  #'cache.lru/lookup
  readers  {:recency #'cache.lru/recency}
  expected {:recency ["c" "b"]}
  actual   {:recency ["b" "c"]}
```

Reading `"b"` should have moved it to the most-recent end. It didn't.

Note **step 12**. The divergence only appears after a particular interleaving
of reads and writes — which is exactly the test nobody writes by hand, and the
reason to describe the rule instead of enumerating the cases.

Put the `swap!` back and the same test passes at `{:traces 200 :max-steps 25}`,
five thousand steps, without a single divergence.

## What to look at

- [src/cache/lru.clj](src/cache/lru.clj) — the implementation. It has **no
  `:require` of quint-connect**. The `:quint/*` keys are inert metadata, and
  nothing in it ever calls the library.
- [test/cache/lru_test.clj](test/cache/lru_test.clj) — the whole test is four
  lines.
- [spec/lru.qnt](spec/lru.qnt) — the specification, including the
  `neverOverCapacity` invariant.
