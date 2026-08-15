# 0006 — Coordinates, namespaces and the annotation key

Status: **partially superseded** by
[0007-annotation-keys.md](0007-annotation-keys.md).

- The coordinates and namespace layout below stand.
- Everything from "One key namespace, reached through an alias" onward does
  not. Keys are `:quint/*`, require nothing, and move as a set via the driver's
  `:key-ns`. The keys namespace is deleted and `:misqualified-key` is gone.

The rest of this document is left as written, as the record of what was decided
and why 0007 had to argue against it.

## Coordinates

```clojure
uno.michelada/quint-connect {:mvn/version "…"}    ; Clojars, unreleased
```

Namespaces live under `uno.michelada.quint-connect`, and source under
`src/uno/michelada/quint_connect/`.

## One key namespace, reached through an alias

Annotation keys are qualified by the library's own namespace, and only by it:

```clojure
:uno.michelada.quint-connect/action
:uno.michelada.quint-connect/args
:uno.michelada.quint-connect/state
:uno.michelada.quint-connect/init
:uno.michelada.quint-connect/halt
```

Nobody types that. The application requires the keys namespace under an alias
and writes auto-resolved keywords:

```clojure
(ns bank.core
  (:require [uno.michelada.quint-connect :as quint]))

(defn deposit {::quint/action "deposit"} [who amount] ...)
(def ^{::quint/state :balances} accounts (atom {}))
```

### Why not a short `:quint/*` key

A short global key needs no require, which is genuinely attractive. It is also
squatting: `quint` is the Quint project's name, not ours, and a global keyword
namespace is a shared resource — any other Clojure library touching Quint would
collide with us, and neither side could fix it without breaking users.

Supporting both spellings was the obvious compromise and it was wrong: two ways
to say one thing, forever, plus a branch in the registry and a paragraph in
every document explaining which to use. One spelling costs each user one
`:require` line, and buys a vocabulary with no footnotes.

### The alias is the user's

`::<alias>/action` resolves through *their* alias, so the name is theirs to
choose. The docs suggest `quint`, because `::quint/action` reads exactly like
the short global key while resolving to the qualified one. `qnt` (after the
`.qnt` file extension) and `mbt` also read well. Nothing in the library depends
on the choice.

### What the require costs

The keys namespace defines no vars and requires nothing — it is a docstring.
Loading it is free at runtime, and it makes the annotations self-documenting:
a reader who does not recognise `::quint/action` can jump to the alias in the
`ns` form and find out what it is.

It does mean an application namespace now depends on quint-connect, so the library
can no longer be strictly test-scope for annotated code. That is accepted. If it
ever becomes a real complaint, the keys namespace can be split into a tiny
`uno.michelada/quint-connect-keys` artifact that the main artifact depends on —
the namespace name would not change, so the split is backwards compatible.

### The one way to get it wrong

An alias pointing at the API namespace instead of the keys namespace:

```clojure
(:require [uno.michelada.quint-connect.core :as quint])   ; wrong namespace
(defn deposit {::quint/action "deposit"} [...])        ; :…quint-connect.core/action
```

This is silent in Clojure — a perfectly valid keyword that nothing reads. The
registry therefore rejects keys qualified by a namespace that starts with
`uno.michelada.quint-connect` but is not exactly it, as `:misqualified-key`,
showing the keyword it found. Verified in
[../notes/clojure-metadata.md](../notes/clojure-metadata.md).

## Why the keys namespace is `uno.michelada.quint-connect` itself

So that one alias serves one purpose cleanly:

- application namespace: `[uno.michelada.quint-connect :as quint]` -> `::quint/action`
- test namespace: `[uno.michelada.quint-connect.core :as q]` -> `q/check`,
  `q/defdriver`

A `…quint-connect.keys` namespace would have made the keyword
`:uno.michelada.quint-connect.keys/action` — longer, and no better. The top
namespace defines no vars precisely so that loading it costs nothing.

## Error keys

`ex-info` data carries `::quint/error` — the same qualified namespace — with an
unqualified keyword value:

```clojure
(ex-info "quint exited 1" {::quint/error :quint-failed :cmd [...] :stderr "..."})
```

One `grep` for `uno.michelada.quint-connect` finds everything this library puts
into someone else's data.
