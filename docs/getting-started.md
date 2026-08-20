# Getting started

From an empty directory to a model-based test that finds a real bug. Every
command below was run against Quint 0.32.0 and Clojure 1.12.5.

§1–§5 exist as a project you can run instead of retype:
[`examples/counter/`](../examples/counter/) is this same spec and the same
implementation, so if the tutorial stops working, running it is how anyone
finds out.

## What you need

- **Clojure** with the `clojure` CLI.
- **Quint** on `PATH`, for generating traces: `npm i -g @informalsystems/quint`.
  Replaying a committed trace needs no Quint at all.

## 1. Write the spec

`spec/counter.qnt` — a counter that refuses to go below zero.

```
module counter {
  var count: int
  var lastOp: str

  action init = all {
    count' = 0,
    lastOp' = "",
  }

  action add(n: int): bool = all {
    n > 0,
    count' = count + n,
    lastOp' = "add",
  }

  action take(n: int): bool = all {
    n > 0,
    count >= n,
    count' = count - n,
    lastOp' = "take",
  }

  action refuse(n: int): bool = all {
    n > 0,
    count < n,
    count' = count,          // refusing changes nothing
    lastOp' = "refused",
  }

  action step = {
    nondet n = 1.to(10).oneOf()
    any { add(n), take(n), refuse(n) }
  }
}
```

Check it runs before going further:

```
quint run spec/counter.qnt --max-steps=4 --verbosity=0
```

## 2. Declare the dependency

`deps.edn`. It belongs in a **test** alias and nowhere else — the annotations
in your application are inert keywords, so nothing in production needs this on
its classpath.

```clojure
{:paths ["src"]
 :deps  {org.clojure/clojure {:mvn/version "1.12.5"}}

 :aliases
 {:test {:extra-paths ["test"]
         :extra-deps  {org.clojars.aldebogdanov/quint-connect
                       {:mvn/version "0.3.0"}

                       io.github.cognitect-labs/test-runner
                       {:git/tag "v0.5.1" :git/sha "dfb30dd"}}
         :main-opts   ["-m" "cognitect.test-runner"]}}}
```

## 3. Annotate the implementation

`src/myapp/core.clj`. Note what is **not** here: no `:require` of
quint-connect. The annotations are inert keywords; nothing calls the library.

```clojure
(ns myapp.core)

(def ^{:quint/state :count}  counter (atom 0))
(def ^{:quint/state :lastOp} last-op (atom ""))

(defn start! {:quint/init true} []
  (reset! counter 0)
  (reset! last-op ""))

(defn add {:quint/action "add"} [n]              ; picks bind by parameter name
  (swap! counter + n)
  (reset! last-op "add"))

(defn take-out {:quint/action "take" :quint/args [:n]} [amount]
  (swap! counter - amount)                       ; parameter is not named n,
  (reset! last-op "take"))                       ; so :quint/args says the order

(defn refuse {:quint/action "refuse"} [_n]
  (reset! last-op "refused"))
```

Two rules worth knowing up front:

- Action handlers receive the `nondet` picks Quint made, bound **by parameter
  name**. Use `:quint/args` when your parameter names differ from the spec's.
- The return value is ignored. State is read afterwards, through the readers.
- A parameter the body ignores is still a pick name. `refuse`'s `[_n]` above
  asks for a pick called `:_n`, which the spec never emits, so it arrives as
  `nil` — fine here, and not a way to skip a pick you actually use.

**The one syntax trap.** Metadata must sit on the symbol or in the attr-map,
never on the `(defn ...)` form:

```clojure
(defn ^{:quint/action "add"} add [n] ...)   ; works
(defn add {:quint/action "add"} [n] ...)    ; works
^{:quint/action "add"} (defn add [n] ...)   ; SILENTLY IGNORED by Clojure
```

The third form is why `:empty-scan` exists and why its message names this.

## 4. Write the test

`test/myapp/model_test.clj`.

```clojure
(ns myapp.model-test
  (:require [clojure.test :refer [deftest]]
            [myapp.core]
            [org.clojars.aldebogdanov.quint-connect.core :as q]
            [org.clojars.aldebogdanov.quint-connect.test :as qt]))

(q/defdriver counter
  {:spec "spec/counter.qnt"
   :scan '[myapp.core]})           ; namespaces to read annotations from

(deftest counter-conforms-to-spec
  (qt/check counter {:traces 10 :max-steps 15}))
```

## 5. Run it

```
clojure -M:test
```

```
Ran 1 tests containing 1 assertions.
0 failures, 0 errors.
```

That ran ten randomly generated traces against your code, comparing every
variable after every step.

## What a failure looks like

Introduce a real bug — make `refuse` change the count:

```clojure
(defn refuse {:quint/action "refuse"} [n]
  (swap! counter + n)              ; refusing should change nothing
  (reset! last-op "refused"))
```

```
FAIL in (counter-conforms-to-spec)
spec and implementation diverged on trace 0 of 10, seed 633005521
diverged at step 1, action "refuse"
  picks    {:n 7}
  handler  #'myapp.core/refuse
  readers  {:count #'myapp.core/counter}
  expected {:count 0}
  actual   {:count 7}
  in spec  {:count 0}
  in app   {:count 7}
  saved      test-resources/quint-connect/failures/counter-seed633005521-trace0.itf.json
  reproduce  cd spec && quint run counter.qnt --mbt --seed=633005521 ...
```

`handler` and `readers` are the payoff of declaring the mapping next to the
code: the failure names the function that diverged and the one that observed
it. The seed was generated for you, and the reproduce line is pasteable.

## 6. Commit the trace that found it

The trace was random. The next run uses a new seed and may not produce it
again — so `qt/check` wrote it to disk before failing, which is the `saved`
line above.

That file is a complete trace in Quint's own ITF encoding. Replaying it needs
no Quint and no randomness:

```clojure
(deftest refuse-does-not-change-the-count      ; the bug of 2026-08-17
  (qt/replay-file counter "test-resources/quint-connect/counter-seed633005521-trace0.itf.json"))
```

Three steps, once per bug:

1. **Run.** A divergence drops the trace in
   `test-resources/quint-connect/failures/`. The name is
   `<spec>-seed<seed>-trace<index>.itf.json` and it is deterministic, so
   re-running the same failing seed rewrites one file rather than leaving a
   pile of near-copies.
2. **Promote what is worth keeping.** Gitignore the drop zone and `mv` the
   trace one directory up, into `test-resources/quint-connect/`. Nothing is
   committed by accident, and a failing run never dirties the repository:

   ```
   # .gitignore
   test-resources/quint-connect/failures/
   ```

3. **Name it in a test.** `qt/replay-file` asserts the way `qt/check` does, and
   fails with the same message. Fix the bug; the test stays.

The result is a regression test that runs in CI on a machine with no Quint
installed, in milliseconds, on exactly the interleaving that broke you once.

`:save-failure` controls the writing, in `qt/check`'s options or in the driver:

```clojure
(qt/check counter {:traces 10 :save-failure false})       ; write nothing
(qt/check counter {:traces 10 :save-failure "target/traces"})  ; write elsewhere
```

`qt/save-failure!` is the same step as a function, for a result you already
have in hand — from `q/check` at the REPL, for instance. It returns the result
with the path added at `[:failure :saved]`.

## 7. Replaying a scripted run

Everything above generates traces at random. A Quint `run` is the opposite: a
scenario someone wrote down because it must keep working.

```
module counterRuns {
  import counter.*

  run addThenTakeTest = init.then(add(7)).then(take(3))
}
```

One catch, and it is the reason this needs anything new. `quint test` does not
accept `--mbt`, and its traces carry no `mbt::actionTaken` — so nothing in the
trace says which action was taken. The spec has to record that itself:

```
  var lastAction: str
  var lastPick: { n: int }

  action add(n: int): bool = all {
    count' = count + n,
    lastAction' = "add",           // what happened
    lastPick' = { n: n },          // and with which pick
  }
```

The driver says where to read them, and `qt/check-run` names the run:

```clojure
(q/defdriver counter
  {:spec        "spec/counter.qnt"
   :main        "counterRuns"
   :scan        '[myapp.core]
   :action-path [:lastAction]      ; a get-in path, applied to the state
   :nondet-path [:lastPick]})

(deftest the-scenario-that-must-keep-working
  (qt/check-run counter {:test "addThenTakeTest"}))
```

`lastAction` and `lastPick` are **not** compared against your application —
each path's root variable leaves the state, because tracking the action is the
spec's own bookkeeping and your code should know nothing about it. If you
forget the paths, you find out twice over: first as a diff against a
`:lastAction` nothing supplies, then as `:unknown-action` naming the option.

For a sum type, end the path at the tag: `:action-path [:lastAction :tag]`
reads `Deposit(...)` as `"Deposit"`.

`quint verify` emits no `mbt::` variables either, so a spec written this way is
also ready for §8.

## 8. Proving an invariant

`check` looks for a divergence in traces Quint made up. `verify` asks Apalache
whether a property can be broken at all, and hands you the counterexample when
it can.

```
  val neverNegative = count >= 0
```

```clojure
(deftest the-counter-never-goes-negative
  (qt/verify counter {:invariant "neverNegative" :max-steps 8}))
```

Three outcomes, and the middle one is the interesting one:

- **It holds.** `:ok? true`, no trace, nothing to replay. Apalache checked
  every reachable state within `:max-steps`, which is a stronger statement than
  any number of random traces.
- **It does not hold, and your code matches the counterexample.** `:ok? false`
  with `:failure` nil. The implementation is faithful and the *spec* is where
  the bug is — you asked for something your own model does not guarantee.
- **It does not hold, and your code disagrees with the counterexample too.**
  `:ok? false` with a `:failure`, reported exactly as `check` reports one.

A violated invariant fails the test in all of the last two cases. Those are two
different facts and the message says which one you have.

The counterexample is saved the way a divergence is, under
`<spec>-<invariant>-counterexample.itf.json`. It is named after the invariant
rather than a seed because Apalache did not roll dice: re-checking the same
invariant rewrites the same file.

Two things to know before you reach for it:

- Counterexamples carry no `mbt::` variables, so the same `:action-path` from
  §7 is required. Without it, replay throws `:unknown-action`.
- It is **slow**. Apalache is downloaded on first use, and a search can take
  minutes on a real spec. Tag those tests and keep them out of the suite you
  run on every save — this repository uses `^:slow` and a `bb test:verify`
  task.

## The whole vocabulary

Six keys, qualified by `quint`, requiring nothing. The first five are the ones
you use; `:quint/driver` only matters when one namespace serves two specs.

| annotation | goes on | means |
| ---------- | ------- | ----- |
| `{:quint/action "add"}` | a function | handler for that spec action |
| `{:quint/args [:who :amount]}` | a function | pick order, when parameter names differ; **required** for multi-arity |
| `{:quint/state :count}` | an atom/ref var, or a getter function | supplies that spec variable |
| `{:quint/state {:var :lastOp :path [:err]}}` | same | supplies it from a nested position |
| `{:quint/state :*}` | a getter function | supplies a whole map of variables; takes `:path` too |
| `{:quint/init true}` | a function | reset the app; runs once per trace |
| `{:quint/halt true}` | a function | stop it; runs after every trace |
| `{:quint/driver :ledger}` | any annotated var | only this driver reads it; absent means all of them |

If `quint` collides with something, a driver can move all six at once with
`:key-ns 'acme.mbt`. See
[decisions/0007-annotation-keys.md](decisions/0007-annotation-keys.md).

## Driver options beyond `:scan`

```clojure
(q/defdriver counter
  {:spec    "spec/counter.qnt"
   :main    "counterTest"                     ; --main module, when needed
   :name    :counter                          ; only needed with :quint/driver
   :scan    '[myapp.core myapp.model-test]
   :ignore  #{:lastOp}                        ; variables not compared
   :compare {:count (fn [expected actual] ...)}
   :actions {"transfer" (fn [picks] ...)}     ; wins over the scan
   :state   {:pending (fn [] ...)}            ; wins over the scan

   :action-path [:lastAction]                 ; for traces with no mbt:: — see §7
   :nondet-path [:lastPick]
   :key-fn  (fn [full-name] ...)})            ; variable name -> keyword
```

`qt/check` options: `:traces`, `:max-steps`, `:max-samples`, `:seed`,
`:save-failure`. Note that `:max-samples` is *attempts* and `:traces` is
*traces written* — Quint requires the former to be at least the latter, so
`:traces` raises it.

`q/replay-file` replays one committed `.itf.json` with no Quint installed, and
`qt/replay-file` is the same thing as an assertion. That is what makes a
recorded failure a deterministic regression test — see §6.

## Rough edges, honestly

- **0.3.0 is an early release.** The API is the one described here and is not
  expected to move, but nothing has been used in anger by anyone but its
  author. The license is [EPL-2.0](../LICENSE), the same as Clojure's.
- **`:missing-state` is not implemented.** A spec variable that no reader
  supplies shows up as a diff against nothing rather than a clear error. The
  driver never reads the spec, so the first trace is what reveals it.
- **A stranded `:key-ns`** — annotations left under the old qualifier — is
  ignored silently unless the whole namespace scans empty.
- **A misspelled `:quint/driver`** is the same shape of silence.
  `{:quint/driver :ledgr}` is read by no driver and reported by none, because
  nothing can tell it from an annotation scoped to a driver you are not
  building right now. An `init` lost this way fails at step 0 of the first
  trace, which is the cheapest place to notice it.
- **No `:setup`/`:teardown`.** Anything that must happen once per `check`, not
  once per trace, goes in `clojure.test/use-fixtures` or a `let` around the
  call.
