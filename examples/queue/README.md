# Example: one implementation, two specs

A bounded queue, specified once and instantiated twice — at capacity 2 and at
capacity 5. One namespace, one spec file, **two drivers**.

Needs `quint` on `PATH` (`npm i -g @informalsystems/quint`): every example
generates its traces rather than replaying committed ones.

```bash
clojure -M:test
```

This is the example for **`:quint/driver`**, the optional sixth annotation key.

## The problem it solves

`queue.core` needs a different setup per spec: the small queue starts with
capacity 2, the large one with 5. Both are `:quint/init`, both live in the same
namespace, and to the registry that is indistinguishable from two vars fighting
over one job:

```
two vars claim :quint/init: #'queue.core/open-large!, #'queue.core/open-small!
```

That error is right for a collision and wrong for this. Scoping tells them
apart:

```clojure
(defn open-small! {:quint/init true :quint/driver :small} []
  (reset! capacity 2)
  (reset! items []))

(defn open-large! {:quint/init true :quint/driver :large} []
  (reset! capacity 5)
  (reset! items []))
```

```clojure
(q/defdriver small
  {:name :small :spec "spec/queue.qnt" :main "smallQueue" :scan '[queue.core]})

(q/defdriver large
  {:name :large :spec "spec/queue.qnt" :main "largeQueue" :scan '[queue.core]})
```

Everything else in `queue.core` — `push`, `reject`, `popItem` — carries no
scope, so **both** drivers read it. That is the default, and it is why the key
can be ignored until the day you need it.

## Note what is shared

Both drivers name the **same `:spec` file**. They differ only in `:main`, the
module of it they run.

This is why the scope names a *driver* and not a spec: naming the spec here
would not distinguish them at all. A driver is what selects a specification —
its map already holds `:spec` — so scoping to `:small` scopes to whatever that
driver runs, without saying it twice and without being unable to tell two
configurations of one spec apart.

## The other error

Scoping an annotation without giving the driver a name has no answer, so it is
not guessed:

```
#'queue.core/open-large! is scoped with :quint/driver but this driver has no
:name, so there is nothing to match it against. Give the driver map a :name,
or drop the scope.
```

## The sharp edge

Driver names are ordinary keywords and nothing validates them. Misspell one —
`{:quint/driver :smal}` — and that annotation is read by no driver and reported
by none, because nothing can distinguish a typo from an annotation scoped to a
driver you are not building right now.

An `init` lost that way fails at step 0 of the first trace with a diff, which
is the cheapest place to find out. It is the same shape of silence as a
stranded `:key-ns`, and it is written down in
[docs/decisions/0009-driver-scope.md](../../docs/decisions/0009-driver-scope.md).

## What to look at

- [spec/queue.qnt](spec/queue.qnt) — one `queue` module, two instantiations.
- [src/queue/core.clj](src/queue/core.clj) — two scoped inits, three unscoped
  actions, and no `:require` of quint-connect.
- [test/queue/model_test.clj](test/queue/model_test.clj) — two drivers over one
  `:scan`.

For the basics start at [../counter/](../counter/); for `verify` see
[../tokens/](../tokens/).
