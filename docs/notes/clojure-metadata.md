# Var metadata: what actually works

Verified against Clojure 1.12.5 by running
[`dev/probes/meta_probe.clj`](../../dev/probes/meta_probe.clj). Every claim
below is observed output, not recollection. Re-run the probe when a claim here
is in doubt.

## Plain qualified keys

```clojure
(ns bank.core)                                 ; no :require

(defn deposit {:quint/action "deposit"} [who amount] ...)
```

`:quint/action` is an ordinary qualified keyword. Nothing needs to be loaded for
the reader to accept it, nothing resolves, and the namespace `quint` need not
exist — Clojure keyword namespaces are just strings. This is why an annotated
namespace can take on no dependency at all
([decisions/0007-annotation-keys.md](../decisions/0007-annotation-keys.md)).

The registry builds the key it looks for from the driver's `:key-ns`:

```clojure
(keyword (str key-ns) "action")   ;=> :quint/action, or :acme.mbt/action
```

The probe exercises both qualifiers over the same namespace, and shows the
consequence: a var annotated `:acme.mbt/action` is simply absent from a scan
looking for `:quint/action`. There is no error, because a keyword carries no
evidence that it was meant to be an annotation.

## Attaching

```clojure
;; WORKS — reader metadata on the symbol
(defn ^{:quint/action "deposit"} deposit [who amount] ...)

;; WORKS — attr-map after the name (and after the docstring, if any).
;; Preferred: more room, reads better with several keys.
(defn withdraw
  "Take money out."
  {:quint/action "withdraw" :quint/args [:who :amount]}
  [account n] ...)

;; SILENTLY DOES NOTHING — metadata on the (defn ...) form is discarded
^{:quint/action "overdraft"}
(defn overdraft [who amount] ...)
```

The third form is the trap: no error, no warning, `(meta #'overdraft)` simply
has no `:quint/action`. The registry must therefore never treat "no
annotations found" as a normal outcome — an empty scan of a namespace listed in
`:scan` is an error, and the message names this pitfall.

## Reading

```clojure
(:quint/action (meta #'deposit))   ;=> "deposit"
(:arglists          (meta #'deposit))   ;=> ([who amount])
(:arglists          (meta #'transfer))  ;=> ([from to] [from to amount])
```

- `:arglists` gives the parameter *names*, which is how picks are matched
  without repeating them in the annotation:
  `(apply deposit (map picks [:who :amount]))`.
- Multi-arity vars yield several arglists. There is no defensible way to pick
  one, so `:quint/args` is required for them.
- `ns-interns` includes private vars; `ns-publics` does not. Use `ns-interns`
  so `defn-` can be annotated.

## Two places metadata can live

```clojure
(def ^{:quint/state :balances} accounts (atom {}))   ; on the var
(def queue (atom [] :meta {:quint/state :pending}))  ; on the atom itself
```

Both are readable: `(meta #'accounts)` and `(meta queue)`. Atoms, refs and
agents implement `IReference`, so the registry reads the var's metadata, then
the reference object's, with the var winning on conflict.

## Telling readers apart

```clojure
(instance? clojure.lang.IDeref (atom 1))      ;=> true
;; likewise for ref, agent, delay, volatile!
(instance? clojure.lang.IDeref some-fn)       ;=> false
```

`IDeref` -> dereference it. A function -> call it with no arguments. Then apply
`:path` with `get-in`, if given.

Note that inspecting the var's *value* does not force a `delay` — only
dereferencing does, which happens at read time, when it should.

## Reloading

A var removed from the source stays interned — with its metadata — until the
namespace is removed or reloaded with `tools.namespace`. This is why the
registry is rebuilt per run and why unused handlers are reported.
