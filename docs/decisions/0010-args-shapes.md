# 0010 — `:quint/args` may build an argument, and the rule spent to allow it

Status: **partially superseded** by
[0011-args-compose-shapes.md](0011-args-compose-shapes.md), which generalised
the grammar below to nest. Everything else here — why the map form exists, the
validation it bought, the `_` rule, the alternatives rejected — stands.

Originally: accepted, with the cost recorded rather than smoothed over.

## Question

An annotated handler takes one positional argument per pick, bound by
parameter name. A great many Clojure functions do not have that shape:

```clojure
(defn transfer [{:keys [from to amount]}] ...)     ; what the application has
```

Before this, that function could not be annotated at all. The arglist has one
parameter, it is a map rather than a symbol, and there is no name in it to bind
a pick to — which [0009](0009-driver-scope.md)'s sibling change turned from
silent nils into `:bad-arglist`.

The documented answer was to wrap it: a one-line function in the test namespace,
or an entry in the driver map's `:actions`, which takes the picks map. Both
work. Both also move the mapping away from the code it describes, which is the
one thing this library claims to be for.

## Decision

An entry in `:quint/args` is a pick name, **or** a map building one argument
out of picks — its keys naming what the handler destructures, its values naming
the picks they come from.

```clojure
(defn transfer
  {:quint/action "transfer" :quint/args [{:from :src :to :dst :amount :amt}]}
  [{:keys [from to amount]}]
  ...)

(defn credit                                   ; the two forms compose
  {:quint/action "credit" :quint/args [:who {:amount :amt}]}
  [who {:keys [amount]}]
  ...)
```

The values are pick names because in the vector form the entries already are.
One rule, read the same way at both levels.

## What it buys, beyond the shape

`:quint/args` had **no validation at all**. Recorded, not recalled:

```
:quint/args :who            -> resolved, then IllegalArgumentException at replay
:quint/args [:wo :amount]   -> resolved, ran, bound [nil 5]
```

The second is the worse one, and it is the reason this change is worth its
cost. `:quint/args` exists precisely for when parameter names differ from pick
names, so a typo in it lands in the one place nobody is looking — and the
failure it produced blamed the implementation for a state the picks had never
reached.

Because the map form writes every pick name down, all of them can be checked.
So this ADR ships two errors rather than one:

- `:bad-args` at construction — not a vector, or an entry that is neither a
  pick name nor a map of keyword to keyword.
- At replay, a pick the handler needs that the trace does not carry, naming the
  ones it does — `:bad-args` when the name came from the annotation,
  `:bad-arglist` when it came from the parameter list. Both keywords now fire
  at two sites, on the precedent `:duplicate-state` set one change earlier.

## The rule this was added against

[CLAUDE.md](../../CLAUDE.md) says: **never grow the annotations into a DSL**,
and anything that cannot be said in one annotation on one var belongs in
`:actions` as a plain function.

The second half is satisfied — this *is* one annotation on one var. The first
half is the one being spent. `:quint/args` was "a vector of keywords", one
sentence with no branches. It is now "a vector of keywords or maps of keyword
to keyword": one branch, not nested, no operators, no evaluation. That is a
grammar, and grammars grow.

What is claimed in exchange is narrow and checkable: the branch exists so that
a function which already takes a map can be annotated where it stands, and it
pays for itself by making the annotation's pick names verifiable for the first
time. If a later change wants a *second* branch, this paragraph is the one to
argue against.

## The check covers both sources, and skips one prefix

Every pick a handler needs is held to the trace, from **either** source. A
parameter misspelled against the spec binds nil exactly as a misspelled
`:quint/args` does:

```clojure
(defn deposit {:quint/action "deposit"} [who amont] ...)   ; picks: who, amount
```

Restricting the check to `:quint/args` was considered and rejected: it would
have left the commoner of the two mistakes in place, in a change whose whole
subject is that binding nil silently is the failure this design most has to
answer for. The keyword differs by source, so the error names the annotation to
go and fix — `:bad-arglist` sends you to the parameter list, `:bad-args` to the
annotation.

The one exemption is a pick name beginning with `_`. That is not a carve-out
invented here; it is a rule this project already wrote down.
[getting-started](../getting-started.md) §3, on `refuse`'s `[_n]`:

> asks for a pick called `:_n`, which the spec never emits, so it arrives as
> `nil` — fine here, and **not a way to skip a pick you actually use**

So `_` is already the sanctioned way to say "this handler does not need that
pick", three of the four examples rely on it, and the check encodes that rather
than contradicting it. The exemption is by name and applies to both sources: if
you write `:quint/args [:_n]`, you have said the same thing the same way.

## Alternatives, and why not

**`:quint/args :*`, meaning "pass the picks map".** Shorter, and symmetric with
`:quint/state :*`. Rejected on two counts: it cannot rename, which is the only
thing `:quint/args` exists to do; and a key mismatch would be pure silence,
with no escape hatch left, since the handler's keys live inside a destructuring
form nothing can reliably parse.

**Infer it from the arglist shape** — one destructuring parameter means "pass
the picks map". Rejected because the inference is ambiguous, and recorded
rather than reasoned:

```
nondet m = Set({from: "a", body: 1}, {from: "b", body: 2}).oneOf()
"mbt::nondetPicks": {"m": {"tag": "Some", "value": {"body": ..., "from": "a"}}}
```

One pick whose value is a record. `(defn recv [{:keys [from body]}] ...)` is a
correct handler for it under `:quint/args [:m]`, and is indistinguishable from
a handler wanting the picks map. Guessing between two valid readings is the
silence this design refuses.

**Leave it alone.** `:actions` covers the case in one line. This is the real
alternative and it was close. What decided it was the second paragraph of the
README: the mapping is declared next to the code it describes. An annotation
that cannot be written for an ordinary Clojure signature sends that mapping
into the driver map, and the exception grows with the codebase.

## Consequences

- The vocabulary is still six keys. This is a value form, not a key.
- `:bad-args` joins the taxonomy, at two sites, and `:bad-arglist` gains a
  second one. No third keyword: which annotation is wrong is more useful than a
  generic "unknown pick", and it costs nothing to say.
- `:quint/args` is validated for the first time, including the flat form that
  predates this.
- A handler wanting the *whole* picks map still belongs in `:actions`. That
  convention is unchanged, and `:bad-arglist` names both routes.
