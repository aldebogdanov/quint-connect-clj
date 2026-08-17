# 0002 — Declarative wiring through var metadata, against a running system

Status: accepted. Key spelling updated by
[0007-annotation-keys.md](0007-annotation-keys.md); both decisions below stand
as made.

## Decision

Two decisions, and the second follows from the first.

**1. The mapping is declared with metadata on the implementation's vars.**

```clojure
(defn deposit {:quint/action "deposit"} [who amount] ...)
(def ^{:quint/state :balances} accounts (atom {}))
(defn start {:quint/init true} [] (mount/start))
```

**2. The system under test is a running application, not a value threaded
through the test.** Actions receive only the `nondet` picks Quint made. Their
return value is ignored. State is read separately, every step, from whatever
declared itself as state — an atom, a ref, or a getter function that may live in
the test namespace.

A driver names the namespaces to read annotations from, plus anything that does
not fit an annotation:

```clojure
(q/defdriver bank
  {:spec "spec/bank.qnt" :main "bankTest"
   :scan '[bank.core bank.system bank.model-test]
   :actions {"transfer" (fn [picks] ...)}})   ; escape hatch, wins on conflict
```

## Why

**The application must not be reshaped for the test tool.** That is the premise
the whole design serves. A threaded context argument would fail it immediately:
every annotated function would need a leading parameter it does not otherwise
want, and would have to return a new system value it does not otherwise
produce. Real Clojure applications keep their state in atoms, refs, mount
states, integrant systems and databases. A model-based testing tool that only
works on functions rewritten as `(fn [ctx] -> ctx)` is a tool for programs
written to be tested by it. So: no context, no return convention, no protocol,
no lifecycle to implement. An annotated namespace adopts nothing at all — not
even a `:require`, since `:quint/action` is a plain keyword
([0007-annotation-keys.md](0007-annotation-keys.md)). Metadata is inert:
nothing calls into quint-connect, and nothing changes shape for it.

**Locality.** A reader of `deposit` sees immediately that it implements a
specified action. The declaration cannot drift away from the function the way a
mapping table in a distant namespace can.

**Parameter names carry the mapping for free.** Quint delivers picks as a
*record* — `{who: "bob", amount: 85}` — and Clojure records parameter names in
`:arglists`. `[who amount]` is already a complete description of how to call the
function with those picks. Nothing is repeated.

**State access is a declaration, not a projection function.** `:quint/state`
on an atom says where a spec variable lives. When the application has no
suitable accessor — the value is behind a database query, or its shape differs
from the spec's — the getter is written in the test namespace and annotated
exactly the same way. Application code stays untouched; the adaptation lives
where adaptation belongs.

## The hazards, and what the design does about each

These are real, and the implementation is required to answer them.

**1. Silent load-order failure.** A scan only sees loaded namespaces.
*Answer:* there is no classpath-wide scan. `:scan` is an explicit list, and the
registry `require`s each namespace before reading it. A typo is an error at
driver construction.

**2. The `^{...} (defn ...)` trap.** Clojure discards that metadata silently.
*Answer:* a namespace in `:scan` that yields no annotations is `:empty-scan`,
and the message names the trap. Silence is never a normal outcome.

**3. Ghost vars after a REPL reload.** A deleted var stays interned with its
metadata.
*Answer:* the registry is rebuilt on every `check`, never cached in a global
atom, and every run reports handlers the traces never exercised.

**4. Ambient state leaking between traces.** With no threaded context, isolation
depends entirely on `:quint/init` doing a complete reset.
*Answer:* state 0 of every trace is compared immediately after `init`, so an
incomplete reset fails at step 0 with a diff instead of poisoning a later trace.
This is the one hazard that the threaded-context design would not have had, and
it is answered by checking rather than by trusting.

**5. One var cannot carry two meanings.** An in-memory adapter and an HTTP
adapter cannot both annotate the same function.
*Answer:* two namespaces, selected by `:scan`; or `:actions` / `:state`
overrides in the driver map.

**6. Not every action is one function call.**
*Answer:* `:actions` takes a plain function of the picks map. An annotation is
the short form, never a DSL in the making.

## Consequences

- Production namespaces carry annotation keys and one `:require` of the keys
  namespace, and nothing else.
- `registry` is the only namespace that reflects, over an explicit
  list of namespaces.
- Readers run once per step, so they must be cheap and free of side effects
  that matter.
- `:quint/init` runs once per trace, so it should be a cheap reset rather
  than a full system start where that is possible.
- The resolved driver — a plain map of data and functions — remains the API the
  engine consumes. Everything downstream of the registry works without
  metadata existing at all.
