# Architecture

Clojure on the JVM only. See
[decisions/0005-clojure-only.md](decisions/0005-clojure-only.md).

## 1. The one idea

The system under test is a **running application**, not a value being threaded
through a fold. quint-connect does three things to it, in a loop:

```
start it       ->   act on it          ->   read it and compare
(:quint/init)       (an annotated fn)       (annotated state readers)
```

The application never learns that Quint exists. It gains metadata keys, which
are inert data, and nothing else: no context parameter, no return-value
convention, no require, no protocol to satisfy. Everything quint-connect needs to
observe is *declared* — on the atom, on the ref, or on a getter function, and
that getter may live in the test namespace when the application has no such
function of its own.

Everything else — the Quint CLI, ITF decoding, metadata scanning,
`clojure.test` integration, reporting — is plumbing around that loop.

## 2. Pipeline

Namespaces are shown by their last segment; all live under
`uno.michelada.quint-connect.*`.

```
 spec.qnt
    |  quint    (shell out: quint run --mbt / quint test / quint verify)
    v
 *.itf.json  ─────────────────────┐  (may be cached in test-resources/)
    |  itf     (decode)           │
    v                             │
 trace data (plain EDN)           │
    |                             │
    |  replay  <── resolved driver  <── registry  (scan :quint/* metadata)
    v                    ^
 result data (plain EDN) |
    |  report      driver map (overrides, complex cases)
    v
 human-readable failure
```

Two phases, deliberately split:

- **generate** — needs the `quint` binary and a subprocess.
- **replay** — no I/O of its own. It calls the functions the driver names and
  compares data. It runs on a trace file committed to the repository, in CI,
  with no Quint installed.

See [decisions/0003-two-phase.md](decisions/0003-two-phase.md).

## 3. Namespaces

All under `uno.michelada.quint-connect`, the Clojars group and artifact
(`uno.michelada/quint-connect`).

| namespace                 | kind       | responsibility                                                                                                                    |
| ------------------------- | ---------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `…quint-connect.itf`      | pure       | ITF JSON -> EDN. Decode `#bigint`/`#map`/`#set`/`#tup`, records, sum types. Normalize variable names, split out `mbt::` metadata. |
| `…quint-connect.registry` | reflective | Read `:quint/*` metadata from an explicit list of namespaces; produce driver data. The only namespace that reflects.              |
| `…quint-connect.replay`   | engine     | Run the loop: init, act, read, compare. No I/O of its own.                                                                        |
| `…quint-connect.report`   | pure       | Result data -> string. Diffs, step context, coverage, reproduce hints.                                                            |
| `…quint-connect.quint`    | impure     | Build and run `quint` commands, collect the ITF files they emit.                                                                  |
| `…quint-connect.core`     | glue       | Public API: `driver`, `defdriver`, `check`, `check-run`, `verify`, `replay-file`.                                                 |
| `…quint-connect.test`     | glue       | `clojure.test` integration, and the one place that writes a failing trace to disk.                                                |
| `…quint-connect.cli`      | impure     | `-main` for generating and caching traces outside a test run (M8).                                                                |

Eight namespaces, none of which application code ever loads. If one passes
~200 lines, that is a signal to stop and reconsider, not to split it reflexively.

`itf` passed it in M7a and stands at 230. Accepted as it is: it is one decoder
of one format, and the halves a split would produce have no separate meaning.
The acceptance is conditional on it stopping there, and M7b is the test of that
— Apalache's ITF dialect and `#unserializable` both land in this namespace. If
they do not fit, the conversation is `itf` versus an `itf.apalache` beside it,
not a reflex split of what is already there.

There is deliberately no keys namespace. An earlier design had one, existing
only to be aliased so that `:quint/action` would resolve to a fully-qualified
key; [0007](decisions/0007-annotation-keys.md) removed it along with the
`:require` it forced on every annotated namespace.

## 4. Annotations

See [decisions/0002-metadata-wiring.md](decisions/0002-metadata-wiring.md) for
why, and [notes/clojure-metadata.md](notes/clojure-metadata.md) for the exact
mechanics, all verified against Clojure 1.12.5 by
[`dev/probes/meta_probe.clj`](../dev/probes/meta_probe.clj).

| metadata key                                  | goes on                     | meaning                                                                             |
| --------------------------------------------- | --------------------------- | ----------------------------------------------------------------------------------- |
| `{:quint/action "deposit"}`                   | function var                | handler for that spec action; called with the picks as arguments                    |
| `{:quint/args [:who :amount]}`                | function var                | pick names in argument order; defaults to `:arglists`; **required** for multi-arity |
| `{:quint/state :balances}`                    | function var / `IDeref` var | supplies the value of that spec variable                                            |
| `{:quint/state {:var :lastError :path [:e]}}` | function var / `IDeref` var | supplies it from a nested position                                                  |
| `{:quint/state :*}`                           | function var / `IDeref` var | supplies a whole map of spec variables, merged                                      |
| `{:quint/init true}`                          | function var                | start or reset the application; run once per trace, before step 0                   |
| `{:quint/halt true}`                          | function var                | stop it; run after every trace, in a `finally`                                      |

Keys are qualified by `quint` and require nothing
([decisions/0007-annotation-keys.md](decisions/0007-annotation-keys.md)):

```clojure
(ns bank.core)                                 ; no :require, no dependency

(defn deposit {:quint/action "deposit"} [who amount] ...)
```

That an annotated namespace pulls in nothing is the point: this library belongs
in a `:test` alias, and an annotation must never be able to drag it onto a
production classpath.

`quint` is a shared keyword namespace, so a driver can move all five keys at
once with `:key-ns`, a symbol — `{:key-ns 'acme.mbt}` reads `:acme.mbt/action`
and friends. One qualifier per driver, no per-key override. The registry is
parameterised on it in exactly one place.

The cost is that an annotation left under the old qualifier while `:key-ns`
points elsewhere is read by nobody and reported by nobody, unless the namespace
scans empty. That gap is accepted and its shape is written down in
[0007](decisions/0007-annotation-keys.md) §"The gap this leaves".

### Actions take picks, nothing else

```clojure
(ns bank.core)

(defn deposit                                ; picks {who, amount}
  {:quint/action "deposit"}
  [who amount]                               ; bound by parameter name
  (swap! accounts update who + amount))

(defn withdraw                               ; parameter names differ from pick names
  {:quint/action "withdraw" :quint/args [:who :amount]}
  [account n]
  ...)
```

The return value is **ignored**. State is never inferred from what a handler
gives back; it is read afterwards, from the declared readers. That is what lets
an ordinary application function be annotated as-is.

Every parameter is a pick name, including the ones the body ignores. `[_who
_amount]` asks for picks called `:_who` and `:_amount`, which no spec emits, so
both arrive as `nil`. That is exactly right for a handler that ignores them —
and it is not a way to skip a pick the handler does need. Name the parameter
after the pick, or give the real order in `:quint/args`.

If the real function needs something that is not a pick — a connection, a system
map, a component — write a one-line wrapper in the test namespace and annotate
that. Nothing in the application changes.

### State is read, not accumulated

```clojure
(ns bank.core)

;; on the var holding a reference
(def ^{:quint/state :balances} accounts (atom {}))

;; or on the reference object itself
(def queue (atom [] :meta {:quint/state :pending}))

;; nested value
(def ^{:quint/state {:var :lastError :path [:err]}} status (ref {:err ""}))
```

```clojure
(ns bank.model-test)   ; readers may live here when the app has none

(defn balances-from-db                       ; a query, called with no arguments
  {:quint/state :balances}
  []
  (into {} (map (juxt :who :amount)) (jdbc/execute! ds ["select who, amount from accounts"])))

(defn everything                             ; one reader, several spec variables
  {:quint/state :*}
  []
  {:balances (balances-from-db) :lastError (last-error)})
```

Resolution by var value: `IDeref` (atom, ref, agent, delay, volatile) is
dereferenced; a function is called with no arguments; `:path` is applied with
`get-in` afterwards. Metadata is read from the var and from the reference
object, with the var winning on conflict.

Readers are called fresh for every comparison — once per step. There is no
cached view of the system, because a cached view is a lie waiting to happen.

### Lifecycle

```
for each trace:
  :quint/init            once per trace       start / reset the app
  read state, compare to trace state 0
  for each following state:
    action handler with picks
    read state, compare
  :quint/halt            in a finally
```

```clojure
(ns bank.system
  (:require [mount.core :as mount]))

(defn start {:quint/init true} [] (mount/start))
(defn stop  {:quint/halt true} [] (mount/stop))
```

There is no per-*check* hook. A fixture that must start once for the whole run
— a container, a database — is `clojure.test`'s own `use-fixtures`, or a `let`
around the call to `check`; both already exist and neither needs this library's
help. A `:setup`/`:teardown` pair in the driver map is an M8 candidate and
nothing more.

Two properties fall out of this shape:

- **Isolation is verified, not assumed.** State 0 of every trace is compared
  immediately after `init`, so an incomplete reset — a leftover atom, an
  unrolled-back transaction — fails at step 0 of trace 2 with a diff, instead of
  corrupting trace 17 mysteriously.
- **`init` runs per trace, so make it cheap.** Fifty traces mean fifty starts.
  A `reset-db!` annotated with `:quint/init` is usually the right
  granularity; a full `mount/start` may not be.

The trace's own first action (`mbt::actionTaken = "init"`, or whatever `--init`
names) is dispatched to the `:quint/init` function. Annotating an
action handler `{:quint/action "init"}` also works and takes precedence;
`:quint/init` exists so that renaming the spec's initializer does not
touch application code.

## 5. The driver map

```clojure
(q/defdriver bank
  {;; --- where the model comes from -------------------------------------
   :spec     "spec/bank.qnt"
   :main        "bankTest"                  ; optional --main module
   :init-action "init"                      ; optional --init action name
   :step-action "step"                      ; optional --step action name

   ;; --- where the annotations are ---------------------------------------
   :scan     '[bank.core bank.system bank.model-test]   ; required'd, then read
   :key-ns   'quint                         ; qualifier for all five keys

   ;; --- complex cases: merged over whatever :scan found, and winning -----
   :actions  {"transfer" (fn [{:keys [from to amount]}] ...)}   ; picks map
   :state    {:pending (fn [] (queue-depth))}                   ; spec var -> 0-arg fn
   :ignore   #{:lastError}
   :compare  {:balances my-fn}

   ;; --- where the action metadata lives ----------------------------------
   ;; Unset, the decoder reads mbt::actionTaken and mbt::nondetPicks. Set,
   ;; they are get-in paths into ordinary spec variables, for the traces that
   ;; carry no mbt:: at all — `quint test` and `quint verify`. The variable
   ;; each path starts at leaves the compared state.
   :action-path [:lastAction]
   :nondet-path [:lastPick]

   :key-fn      (fn [full-name] ...)})      ; variable name -> keyword
```

Handlers written in `:actions` take the **picks map**, not positional arguments,
because a complex case usually wants to destructure it. Annotated functions take
positional arguments because that is what makes annotating an existing function
possible without touching it.

Resolution, in order: `require` each `:scan` namespace -> read `ns-interns`
metadata -> assemble actions, readers and lifecycle -> merge the driver map on
top -> validate. The merged result is the **resolved driver**, an ordinary map
of data and functions, and it is what `uno.michelada.quint-connect.replay` consumes. Nothing
downstream of the registry knows that metadata exists.

`(q/driver m)` is a function returning the resolved driver; `q/defdriver` is
`(def name (q/driver m))` and nothing more.

### Validation, at driver construction

- `:empty-scan` — a namespace in `:scan` yielded no annotations. Almost always
  the `^{...} (defn ...)` trap; the message says so.
- `:duplicate-action`, `:duplicate-state` — two vars claim the same name. A
  driver-map `:actions` or `:state` entry replacing one of them does **not**
  suppress this. The annotations are still ambiguous, and quietly picking a
  winner is the failure this layer exists to prevent; the override says which
  function to call, not which of two declarations was meant.
- `:duplicate-init`, `:duplicate-halt` — two vars claim the same lifecycle
  role, in one namespace or across the `:scan`. First-one-wins is not an
  option here: the loser would never run, and an `init` that never runs
  reappears as a divergence in a later trace that has nothing to do with it.
- `:ambiguous-arity` — multi-arity var without `:quint/args`.

At replay time, because each of these needs the trace: `:no-init` (it starts
with an action nothing is annotated for), `:unknown-action`,
`:anonymous-action`, `:state-read-failed`, plus a coverage report of handlers
the traces never exercised.

A spec variable that no reader supplies is **not** caught at construction. The
driver never sees the spec, only the annotations, so the first trace is what
reveals it — as a step-0 diff of that variable against nothing. Turning it into
a `:missing-state` at construction would mean parsing the spec, which is a
non-goal (§10); doing it at the first comparison instead is open, and unbuilt.

## 6. Data shapes

### Decoded trace

```clojure
{:source "bank.qnt"
 :vars   [:balances :lastError]
 :states [{:index 0 :action "init"      :picks {}
           :state {:balances {"alice" 0} :lastError ""}}
          {:index 1 :action "overdraft" :picks {:who "bob" :amount 85}
           :state {:balances {"alice" 0} :lastError "insufficient funds"}}]}
```

### Result

```clojure
{:ok?      false
 :seed     "42"
 :traces   50
 :steps    231                        ; states actually replayed
 :coverage {:used   {"deposit" 88 "withdraw" 40}
            :unused #{"overdraft"}}   ; handler declared, spec never emitted it
 :cmd      ["quint" "run" "bank.qnt" ...]   ; the reproduce line
 :dir      "/path/to/spec"                  ; where to run it
 :failure  {:trace      7
            :trace-name "run_7.itf.json"
            :trace-json "{...}"             ; carried, not a path: see below
            :step       3
            :action     "withdraw"
            :handler    #'bank.core/withdraw    ; the var, when it came from an annotation
            :picks      {:who "alice" :amount 20}
            :expected   {:balances {"alice" 30}}
            :actual     {:balances {"alice" 50}}
            :readers    {:balances #'bank.core/accounts}
            :diff       [{...} {...} {...}]     ; clojure.data/diff
            :cause      nil                     ; ex-info if the handler threw
            :saved      "test-resources/quint-connect/failures/bank-seed42-trace7.itf.json"}}
```

The failing trace travels as JSON rather than as a filename because the
scratch directory Quint wrote it to is deleted before `check` returns.
`:saved` is where it landed afterwards — see §7.

Result data is the contract. `uno.michelada.quint-connect.report` and `uno.michelada.quint-connect.test` are
consumers of it, not producers of parallel truth. Carrying the handler and
reader *vars* is the payoff of the annotation design: a failure points at the
file and line of the function that diverged and at the one that observed it.

## 7. Modes

| mode         | trace source                          | when to use                                                   |
| ------------ | ------------------------------------- | ------------------------------------------------------------- |
| `check`      | `quint run --mbt` (N random traces)   | the default: broad conformance testing                        |
| `check-run`  | `quint test --match ^name$`           | one scripted scenario from a Quint `run` (see caveat below)   |
| `verify`     | `quint verify --invariant I`          | prove the spec, then replay the counterexample if there is one |
| `replay-file`| a committed `.itf.json`               | regression tests, CI without Quint                              |

Caveat, verified against Quint 0.32.0: **`quint test` does not accept `--mbt`**
and its traces contain no `mbt::actionTaken`/`mbt::nondetPicks`. Same for
`quint verify`. Only `quint run --mbt` yields action metadata. So `check-run`
and `verify` require the spec to track the action itself in a normal variable,
which is what `:action-path` / `:nondet-path` are for — the reason those two
modes are a milestone rather than a flag. Documented in
[notes/itf-format.md](notes/itf-format.md), and demonstrated by
`dev/fixtures/tracked.qnt`, which is the same bank as `bank.qnt` with two
bookkeeping variables added and therefore replays against the same unchanged
`dev/bank/core.clj`.

`verify` is not implemented yet; `check-run` is.

### From a random failure to a committed trace

`check` and `replay-file` are the two ends of one workflow, and the file
between them is the whole point of generating traces at random: a trace that
found a bug once must be able to find it again on a machine with no Quint.

```
qt/check          divergence -> save-failure! -> test-resources/quint-connect/failures/
                                                     |  mv, by a human
                                                     v
qt/replay-file    test-resources/quint-connect/<spec>-seed<n>-trace<i>.itf.json
```

Two properties make this a workflow rather than a pile of files:

- **The name is deterministic.** Seed and trace index identify the spec trace,
  so re-running a failing seed rewrites one file. Nothing accumulates.
- **The drop zone is not the archive.** `failures/` is gitignored and written
  to on every divergence; promotion into `test-resources/quint-connect/` is a
  `mv` a human performs. A failing test never dirties the repository, and a
  committed trace is always one someone chose to keep.

Writing the file is the only reason `uno.michelada.quint-connect.test` touches
the filesystem, and it is why the step lives there rather than in `core`:
`q/check` stays a function of data returning data.

## 8. Platform

JVM only, and the design spends that freely rather than pretending otherwise:

- `ns-interns` + `meta` for the registry — reflection over vars, nothing deeper.
- `clojure.java.process/exec` (Clojure 1.12, no dependency) for running `quint`.
- `clojure.data.json` for parsing, behind one function in `uno.michelada.quint-connect.itf`.
- `clojure.data/diff` for diffing.
- `java.nio.file.Files/createTempDirectory` for trace scratch space.
- Files are `.clj`. No reader conditionals anywhere.
- Integers decode to `long` when they fit and `clojure.lang.BigInt` otherwise.
  See the large-integer trap in [notes/itf-format.md](notes/itf-format.md) —
  a Quint bug, with its own fixture in M1.

## 9. Errors

Every failure mode is an `ex-info` with `:quint/error` set to a keyword,
never a bare string or a bare `assert`:

```clojure
(ex-info "quint exited 1" {:quint/error :quint-failed :cmd [...] :stderr "..."})
```

The keywords:

```clojure
:quint-not-found  :quint-failed  :no-traces  :test-failed  :bad-itf
:bad-decode-path  :name-collision  :empty-scan  :duplicate-action
:duplicate-state  :duplicate-init  :duplicate-halt  :ambiguous-arity
:no-init  :unknown-action  :anonymous-action  :state-read-failed
:save-failed
```

That list is the whole set, and it is checked against the source rather than
maintained by hand: `grep -rho '(fail :[a-z-]*' src/` plus the two passed to
`registry/no-duplicates!`.

A *state mismatch* is not an exception — it is a value in the result map.
Exceptions are for broken setups; divergence is the expected output of a
testing tool.

## 10. Non-goals

- Parsing or evaluating Quint in Clojure. We shell out.
- Generating Quint specs from Clojure code, or vice versa.
- Requiring the application to adopt a shape: no context argument, no return
  convention, no protocol, no require. Annotations are inert.
- Guessing which function implements an action. Annotations are explicit; only
  the *shape of the call* is inferred, from parameter names.
- Type declarations in annotations. ITF carries the types; `:int` in an
  annotation would be a second, unchecked source of truth.
- A DSL for multi-step actions. Complex cases are Clojure functions, in the test
  namespace or in `:actions`.
- A bespoke test runner. See [decisions/0001-library.md](decisions/0001-library.md).
- ClojureScript. See [decisions/0005-clojure-only.md](decisions/0005-clojure-only.md).

## 11. Known risks

| risk                                                       | mitigation                                                                                                 |
| ---------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `^{...} (defn ...)` silently loses metadata                | `:empty-scan` error naming the trap; documented in three places                                            |
| an annotation stranded under the wrong `:key-ns`           | **none** — caught only if the namespace scans empty; accepted in [0007](decisions/0007-annotation-keys.md) |
| two scanned namespaces both annotating `:quint/init`       | `:duplicate-init` / `:duplicate-halt` at construction, naming both vars                                    |
| a spec variable no reader supplies                         | diverges against nothing on the first state carrying it; there is no `:missing-state` — see §5             |
| an `init` that does not fully reset leaks between traces   | state 0 is compared right after `init`; a leak fails immediately                                           |
| `init` per trace is expensive for a full system start      | documented; prefer a cheap reset function over `mount/start`                                               |
| ghost vars surviving a REPL reload                         | registry rebuilt per run; unused-handler coverage report                                                   |
| forgotten `require` hiding a handler                       | `:scan` is explicit and required'd; no classpath-wide scan                                                 |
| annotations drifting from the spec's action names          | `:unknown-action` plus the coverage report on every run                                                    |
| reading state has side effects (a query, a `delay`)        | readers are called once per step; document that they must be cheap and idempotent                          |
| `--mbt` is experimental and may change                     | pin the tested version, assert `quint --version`, fixtures                                                 |
| anonymous actions (`any { all {a, b} }`) produce `""`      | fail loudly with the rewrite instruction, as quint-connect does                                            |
| module-prefixed var names (`bankTest::bank::balances`)     | strip to the last segment, error on collision, `:key-fn` escape                                            |
| Quint mis-encodes integers >= 10^15 on its default backend | decode the broken form too; fixtures for both backends                                                     |
