# Getting started

From an empty directory to a model-based test that finds a real bug. Every
command below was run against Quint 0.32.0 and Clojure 1.12.5.

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

`deps.edn`. quint-connect is not on Clojars yet, so use `:local/root` (or a
`:git/url` coordinate). It belongs in a **test** alias and nowhere else.

```clojure
{:paths ["src"]
 :deps  {org.clojure/clojure {:mvn/version "1.12.5"}}

 :aliases
 {:test {:extra-paths ["test"]
         :extra-deps  {uno.michelada/quint-connect {:local/root "../quint-connect-clj"}

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
            [uno.michelada.quint-connect.core :as q]
            [uno.michelada.quint-connect.test :as qt]))

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
  reproduce  cd spec && quint run counter.qnt --mbt --seed=633005521 ...
```

`handler` and `readers` are the payoff of declaring the mapping next to the
code: the failure names the function that diverged and the one that observed
it. The seed was generated for you, and the reproduce line is pasteable.

## The whole vocabulary

Five keys, qualified by `quint`, requiring nothing.

| annotation | goes on | means |
| ---------- | ------- | ----- |
| `{:quint/action "add"}` | a function | handler for that spec action |
| `{:quint/args [:who :amount]}` | a function | pick order, when parameter names differ; **required** for multi-arity |
| `{:quint/state :count}` | an atom/ref var, or a getter function | supplies that spec variable |
| `{:quint/state {:var :lastOp :path [:err]}}` | same | supplies it from a nested position |
| `{:quint/state :*}` | a getter function | supplies a whole map of variables |
| `{:quint/init true}` | a function | reset the app; runs once per trace |
| `{:quint/halt true}` | a function | stop it; runs after every trace |

If `quint` collides with something, a driver can move all five at once with
`:key-ns 'acme.mbt`. See
[decisions/0007-annotation-keys.md](decisions/0007-annotation-keys.md).

## Driver options beyond `:scan`

```clojure
(q/defdriver counter
  {:spec    "spec/counter.qnt"
   :main    "counterTest"                     ; --main module, when needed
   :scan    '[myapp.core myapp.model-test]
   :ignore  #{:lastOp}                        ; variables not compared
   :compare {:count (fn [expected actual] ...)}
   :actions {"transfer" (fn [picks] ...)}     ; wins over the scan
   :state   {:pending (fn [] ...)}})          ; wins over the scan
```

`qt/check` options: `:traces`, `:max-steps`, `:max-samples`, `:seed`. Note that
`:max-samples` is *attempts* and `:traces` is *traces written* — Quint requires
the former to be at least the latter, so `:traces` raises it.

`q/replay-file` replays one committed `.itf.json` with no Quint installed,
which is what makes a recorded failure a deterministic regression test.

## Rough edges, honestly

- **Not released.** No Clojars artifact and no license yet.
- **`:missing-state` is not implemented.** A spec variable that no reader
  supplies shows up as a diff against `{}` rather than a clear error.
- **Only `quint run --mbt` drives anything.** `quint test` and `quint verify`
  emit no action metadata; that is M7.
- **A stranded `:key-ns`** — annotations left under the old qualifier — is
  ignored silently unless the whole namespace scans empty.
