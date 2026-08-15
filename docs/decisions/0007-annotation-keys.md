# 0007 — Annotation keys are `:quint/*`, and the driver can move them

Status: accepted. Supersedes the annotation-key decision in
[0006-names.md](0006-names.md); that ADR's coordinates and namespace layout
stand unchanged.

## Decision

Annotation keys live in the `quint` keyword namespace and need no `require`:

```clojure
:quint/action
:quint/args
:quint/state
:quint/init
:quint/halt
```

An annotated namespace requires nothing:

```clojure
(ns bank.core)                                 ; no :require, no dependency

(defn deposit {:quint/action "deposit"} [who amount] ...)
(def ^{:quint/state :balances} accounts (atom {}))
```

A driver may move the whole vocabulary to a namespace of its own choosing with
`:key-ns`, a symbol:

```clojure
(q/defdriver bank
  {:spec   "spec/bank.qnt"
   :scan   '[bank.core]
   :key-ns 'acme.mbt})          ; now reads :acme.mbt/action, :acme.mbt/state, …
```

`:key-ns` moves all five keys together. There is no per-key override and no
second accepted spelling within one driver.

## Why this reverses 0006

0006 chose `:uno.michelada.quint-connect/action`, reached through an alias, to
avoid squatting on a shared keyword namespace. The reasoning was sound and the
cost was mispriced.

That cost was one `:require` in application code — which means the library is a
dependency of the application, not of its tests. For a testing tool that is the
wrong direction. `quint-connect` should sit in a `:test` alias and nowhere else,
and no annotation should be able to drag it into a production classpath. The
require was small, but it was the one thing standing between "annotations are
inert" and "annotations are inert *and* free".

The squatting objection was real and is answered rather than dismissed: `:key-ns`
means a collision with another Clojure-side Quint library is a one-line fix in
the driver, made by the person who hit it, without either library changing. A
shared default plus a local override is the ordinary way this is handled; 0006
rejected it before the dependency-direction cost was on the table.

## What this removes

The keys namespace `uno.michelada.quint-connect` is deleted. It existed only to
be aliased, and nothing aliases it now. `src/uno/michelada/quint_connect/` holds
ordinary library code and nothing else.

`:misqualified-key` is dropped along with it. It caught exactly one mistake — an
alias pointing at the API namespace — and without aliases that mistake cannot be
made.

## The gap this leaves, stated plainly

Nothing detects annotations written under the wrong qualifier:

```clojure
;; driver says {:key-ns 'acme.mbt}
(defn deposit {:quint/action "deposit"} [who amount] ...)   ; never read
```

`:empty-scan` catches this only when a namespace yields *no* annotations at all.
A half-migrated namespace — some vars moved to `:acme.mbt/*`, some still on
`:quint/*` — scans non-empty, and the stragglers are silently ignored. That
contradicts the rule in CLAUDE.md that an unread annotation must always become
an error, and it is accepted here with the contradiction visible rather than
smoothed over.

The check was dropped deliberately: `:key-ns` is expected to be rare, and a
validator that guesses which qualifiers "look like" annotations is a heuristic
that will eventually be wrong about someone's unrelated metadata. If the gap
draws blood, the cheap fix is to widen `:empty-scan` rather than to reinstate a
namespace-similarity check — count vars carrying an `action`/`args`/`state`/
`init`/`halt`-named key under *any* qualifier, and report the ones the
configured `:key-ns` did not read.

## Error keys are not affected

`ex-info` data carries `:quint/error`, fixed, whatever `:key-ns` says:

```clojure
(ex-info "quint exited 1" {:quint/error :quint-failed :cmd [...] :stderr "..."})
```

`:key-ns` configures the vocabulary the user *writes*. Error data is what the
library *emits*, and a caller catching an exception should not have to know how
some driver was configured to know which key to look in.
