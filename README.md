# quint-connect

Model-based testing for Clojure, driven by [Quint](https://quint-lang.org/)
specifications.

Status: **0.5.1**. Every planned milestone in
[docs/roadmap.md](docs/roadmap.md) is done — decoding, replay, the annotation
registry, the Quint CLI, the public API, failure artifacts, scripted runs and
`verify`. `bb test` runs a real model-based test end to end, needing no Quint.
What is still rough is listed honestly in
[getting-started](docs/getting-started.md#rough-edges-honestly).

## What it does

You write a formal spec of your system in Quint. Quint generates traces from it
(sequences of `state -> action -> state`). quint-connect starts your application,
replays those traces against it, and fails the test at the first step where the
application's state diverges from the specification's state.

```
spec.qnt  --quint run --mbt-->  ITF traces  --replay-->  your running app
                                                  |
                                            state diff on divergence
```

The mapping between spec and code is declared with metadata, next to the code it
describes. The application never learns that Quint exists: no context argument,
no return-value convention, no require. This is the Clojure counterpart of
[quint-connect](https://github.com/quint-co/quint-connect) (Rust).

## Coordinates

```clojure
;; belongs in a :test alias and nowhere else
org.clojars.aldebogdanov/quint-connect {:mvn/version "0.5.1"}
```

## Usage

Annotate the application. The keys are plain keywords needing no `require`, so
an annotated namespace takes on no dependency — this library belongs in your
`:test` alias and nowhere else. Actions take the `nondet` picks Quint made,
bound by parameter name, and their return value is ignored:

```clojure
(ns bank.core)                                        ; no :require, no deps

(def ^{:quint/state :balances} accounts (atom {}))

(defn deposit
  {:quint/action "deposit"}
  [who amount]
  (swap! accounts update who (fnil + 0) amount))

(defn withdraw
  {:quint/action "withdraw"}
  [who amount]
  (when (>= (get @accounts who 0) amount)
    (swap! accounts update who - amount)))
```

State is read back from whatever declares itself as state — an atom, a ref, or a
getter function. Getters can live in the test namespace when the application has
none of its own:

```clojure
(ns bank.model-test
  (:require [bank.core :as bank]
            [clojure.test :refer [deftest]]
            [org.clojars.aldebogdanov.quint-connect.core :as q]    ; the API
            [org.clojars.aldebogdanov.quint-connect.test :as qt])) ; the bridge

(defn last-error                             ; called with no arguments
  {:quint/state :lastError}
  []
  (bank/current-error))

(defn reset-app {:quint/init true} []   ; runs once per trace, before step 0
  (reset! bank/accounts {"alice" 0 "bob" 0}))

(q/defdriver bank
  {:spec "spec/bank.qnt"
   :main "bankTest"
   :scan '[bank.core bank.model-test]        ; namespaces to read annotations from

   ;; anything that is not one function call goes here, and wins on conflict
   :actions {"transfer" (fn [{:keys [from to amount]}] ...)}})

(deftest bank-conforms-to-spec
  (qt/check bank {:traces 50 :max-steps 20 :seed 42}))
```

Careful — this is the one syntax trap. Metadata must sit on the *symbol* or in
the attr-map, never on the `(defn ...)` form:

```clojure
(defn ^{:quint/action "deposit"} deposit [who amount] ...)  ; works
(defn deposit {:quint/action "deposit"} [who amount] ...)   ; works
^{:quint/action "deposit"} (defn deposit [who amount] ...)  ; SILENTLY IGNORED
```

`q/check` is an ordinary function returning ordinary data, so it works from the
REPL, from `clojure.test`, from Kaocha, or from anything else. `qt/check` is the
one-line bridge that turns that data into an assertion.

When the implementation diverges, that assertion fails with:

```
spec and implementation diverged on trace 0 of 5, seed 42
diverged at step 6, action "deposit"
  picks    {:amount 11, :who "alice"}
  handler  #'bank.core/deposit
  readers  {:balances #'bank.core/accounts}
  expected {:balances {"alice" 11, "bob" 0}}
  actual   {:balances {"alice" 12, "bob" 0}}
  in spec  {:balances {"alice" 11}}
  in app   {:balances {"alice" 12}}
  saved      test-resources/quint-connect/failures/bank-seed42-trace0.itf.json
  reproduce  cd spec && quint run bank.qnt --mbt --seed=42 ...
```

The handler and reader vars are the payoff of declaring the mapping next to the
code: the failure points at the function that diverged and at the one that
observed it. The reproduce line is pasteable.

### When the handler's shape is not one pick per parameter

Picks bind by parameter name, which covers most handlers and requires nothing.
`:quint/args` is for the rest, and it says **the shape of each argument, with
pick names where its values go** — the exact inverse of the destructuring the
handler performs on it:

```clojure
;; parameter names differ from the spec's pick names
(defn withdraw {:quint/action "withdraw" :quint/args [:who :amount]}
  [account n] ...)

;; the function the application already had takes a map
(defn transfer {:quint/action "transfer" :quint/args [{:from :src :to :dst}]}
  [{:keys [from to]}] ...)

;; and shapes nest as far as the parameter list takes them apart
(defn move {:quint/action "move" :quint/args [[:x :y]]}          [[x y]] ...)
(defn plot {:quint/action "plot" :quint/args [{:pos [:x :y]}]}   [{:keys [pos]}] ...)
```

Read each annotation against the parameter list beside it: one composes what
the other takes apart. A keyword is a leaf, vectors and maps nest, and every
leaf is a pick the trace is held to — a name no trace carries is an error at
the step it was needed, not a silent `nil`. Prefix a parameter with `_` to say
the handler does not need that pick.

Two shapes look alike and are not. A handler destructuring **one pick whose
value is a record or a tuple** wants `:quint/args [:m]`; a handler that wants
**the whole picks map** belongs in the driver map's `:actions`, where that is
the calling convention. Annotating the second shape without `:quint/args` is an
error that names both.

See [decisions/0011-args-compose-shapes.md](docs/decisions/0011-args-compose-shapes.md).

### The trace outlives the run

That `saved` line is a random trace made permanent. Move it out of the
gitignored drop zone, commit it, and name it in a test:

```clojure
(deftest deposit-is-not-off-by-one              ; the bug of 2026-08-17
  (qt/replay-file bank "test-resources/quint-connect/bank-seed42-trace0.itf.json"))
```

That test needs no Quint, no randomness and no seed: it replays exactly the
interleaving that broke you, in milliseconds, in CI. The workflow is
[docs/getting-started.md](docs/getting-started.md) §6.

### About the keys

Five keys, qualified by `quint`, requiring nothing: `:quint/action`,
`:quint/args`, `:quint/state`, `:quint/init`, `:quint/halt`. A sixth,
`:quint/driver`, exists only for the case where one namespace serves two specs
and each wants its own lifecycle:

```clojure
(defn open-ledger! {:quint/init true :quint/driver :ledger} [] ...)
(defn open-cache!  {:quint/init true :quint/driver :cache}  [] ...)

(q/defdriver ledger {:name :ledger :spec "spec/ledger.qnt" :scan '[app.system]})
```

An annotation without it belongs to every driver, so you can ignore the key
until you need it. See
[docs/decisions/0009-driver-scope.md](docs/decisions/0009-driver-scope.md).

`quint` is a shared keyword namespace and the Quint project's name, not ours,
so a driver can move the whole vocabulary out of the way:

```clojure
(q/defdriver bank
  {:spec   "spec/bank.qnt"
   :scan   '[bank.core]
   :key-ns 'acme.mbt})          ; now reads :acme.mbt/action, :acme.mbt/state, …
```

`:key-ns` moves all six keys together; there is no per-key override and no
second spelling within one driver. Note the sharp edge: if you set `:key-ns`
and leave an annotation on `:quint/*`, nothing reads it and nothing warns you,
unless the whole namespace scans empty. See
[docs/decisions/0007-annotation-keys.md](docs/decisions/0007-annotation-keys.md).

## Examples

Four runnable projects, each teaching one thing. Start at the first.

| example | what it shows |
| ------- | ------------- |
| [counter/](examples/counter/) | the [getting-started](docs/getting-started.md) tutorial, as a project rather than a page |
| [lru/](examples/lru/) | a cache bug that only appears after a particular interleaving — the test nobody writes by hand |
| [tokens/](examples/tokens/) | `verify`: sampling versus proving, and why a green `verify` does not mean your code is right |
| [queue/](examples/queue/) | `:quint/driver`: one implementation and one spec file, two drivers with different setup |

Each is a self-contained project. They need `quint` on `PATH`
(`npm i -g @informalsystems/quint`), because they generate traces rather than
replaying committed ones:

```bash
cd examples/counter && clojure -M:test
```

Every example also ships the broken version of itself, so you can see what a
failure reads like without editing anything:

```bash
cd examples/lru && clojure -M:test:broken     # fails, on purpose
```

They depend on the released coordinate, so a copied directory runs unchanged.
Working on the library itself? `clojure -M:test:local` swaps in the working
tree — see [CONTRIBUTING.md](CONTRIBUTING.md).

## Requirements

- Clojure 1.12+ on the JVM. ClojureScript is out of scope
  ([decision](docs/decisions/0005-clojure-only.md)).
- [Quint](https://github.com/informalsystems/quint) on `PATH` for trace
  *generation* (developed against 0.32.0). Trace *replay* needs nothing but
  Clojure — cached ITF files run in CI without Quint installed.
- Apalache (fetched by `quint verify`) only for the verification mode.
- [babashka](https://babashka.org/) only to use this repository's `bb` tasks,
  which are shorthand for `clojure` commands and are not needed to consume the
  library.

## Repository layout

```
src/org/clojars/aldebogdanov/quint_connect/   library code (.clj)
test/org/clojars/aldebogdanov/quint_connect/  tests
test-resources/quint-connect/  traces promoted from a failing run
examples/counter/              the getting-started tutorial, runnable
examples/lru/                  a bug found only by interleaving
examples/tokens/               verify: an invariant proved, and one broken
examples/queue/                two drivers over one namespace
dev/bank/                      annotated toy implementation of bank.qnt
dev/fixtures/                  example Quint spec + recorded ITF traces
dev/probes/                    scripts that verified the claims in the docs
docs/architecture.md
docs/roadmap.md
docs/decisions/                ADRs — why the design is what it is
docs/notes/                    observed behaviour of Quint and of var metadata
CLAUDE.md                      working agreement for AI-assisted changes
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). The test suite that replays committed
traces needs nothing but the `clojure` CLI; the rest additionally needs `quint`
on `PATH`, and the `verify` tests need Apalache, which `quint` fetches.

The `bb` tasks in this repository are [babashka](https://babashka.org/)
shorthand for single `clojure` commands, and CONTRIBUTING gives the long forms
for anyone who would rather not install it.

## License

Copyright © 2026 Aleksandr Bogdanov

Distributed under the [Eclipse Public License 2.0](LICENSE), the same license
as Clojure itself.
