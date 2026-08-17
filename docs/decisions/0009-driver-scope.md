# 0009 — `:quint/driver`, a sixth key, and the rule it was added against

Status: accepted, with the reservation below recorded rather than smoothed over.

## Question

One namespace, two specs. An application with a ledger spec and a cache spec
has one `system` namespace, and each spec wants a different `init` — a
different reset, a different fixture, a different amount of the world started.

Since [0008](0008-release.md) that is an error: two vars claiming `:quint/init`
are `:duplicate-init`, whether they are in one namespace or across the `:scan`.
That error is right for a collision and wrong for this, and the two are
indistinguishable to the registry.

## Decision

A sixth annotation key, optional, scoping an annotation to named drivers:

```clojure
(ns app.system)

(defn open-ledger! {:quint/init true :quint/driver :ledger} [] ...)
(defn open-cache!  {:quint/init true :quint/driver :cache}  [] ...)

(defn post {:quint/action "post" :quint/driver #{:ledger :cache}} [n] ...)
(defn audit {:quint/action "audit"} [] ...)          ; every driver
```

```clojure
(q/defdriver ledger {:name :ledger :spec "spec/ledger.qnt" :scan '[app.system]})
(q/defdriver cache  {:name :cache  :spec "spec/cache.qnt"  :scan '[app.system]})
```

- A keyword, or a set of them. **Absent means every driver**, so nothing
  written before this key existed changes, and the key stays out of the way of
  the common case where a namespace serves one spec.
- It moves with `:key-ns` like the other five. A per-key qualifier is what
  [0007](0007-annotation-keys.md) refused and this does not reintroduce it.
- Scoping happens **before** duplicate detection. Two inits for two different
  drivers are not a collision; two for the same driver still are, and there is
  a test that says so.
- A scoped annotation in a driver with no `:name` is `:unnamed-driver`. The
  question "does this apply to me?" has no answer there, and both answers are
  wrong in a way nothing would report.

## The rule this was added against

[CLAUDE.md](../../CLAUDE.md) says the five keys are the whole vocabulary and
that **adding one needs a reason from a real spec, not a hypothetical one**.

There is no such spec in this repository. Every driver here — `bank`,
`tracked`, `checked`, the two examples — scans a namespace serving exactly one
of them, and `dev/bank/core.clj` is shared by three drivers without conflict
because they want identical lifecycle. The case above is a real shape and a
common one, but it was reasoned about rather than met.

That was raised twice and the key was asked for anyway. It is the author's
call, and this paragraph is here so that the call is legible later rather than
looking like the rule was forgotten. The specific risk taken: a scoping key is
much harder to remove than to add, and if the first real multi-spec application
wants scoping by *spec file* or by *module* rather than by driver name, this
key will be the wrong shape and will already have users.

## Alternatives, and why not

**Split the namespace.** `app.system-ledger` and `app.system-cache`, each in
its own `:scan`. Needs no new vocabulary and works today. It was rejected as
the only answer because it makes the file layout follow the test tooling, which
is the thing this project keeps refusing to do everywhere else — the
application is not reshaped for the tool.

**Driver-map `:init` / `:halt` overrides**, symmetric with `:actions` and
`:state`. Rejected: an override says which function to call, not which of two
declarations was meant, and [the override decision](../architecture.md) settled
that overriding does not silence an ambiguity. It would also leave the
annotations in the namespace still ambiguous and still erroring.

## Consequences

- The vocabulary is six keys. `action`, `args`, `state`, `init`, `halt`,
  `driver`.
- `:name` joins the driver map, and is only meaningful when scoping is used.
- `:unnamed-driver` joins the error list.
- `:empty-scan` now means "no annotations at all", not "nothing for this
  driver". A namespace whose annotations are all scoped elsewhere is legitimately
  empty for this driver and must not be reported as the `^{...} (defn ...)`
  trap.
