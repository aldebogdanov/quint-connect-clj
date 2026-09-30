# Example: two-phase commit, specified with Choreo

A coordinator and three participants agree to commit or abort, together: the
classic protocol for an atomic commit across nodes. The spec is
[Choreo](https://github.com/informalsystems/choreo)'s own two-phase commit;
the implementation is eighty lines of Clojure, docstrings included, over two
atoms.

Needs `quint` on `PATH` (`npm i -g @informalsystems/quint`): every example
generates its traces rather than replaying committed ones.

```bash
clojure -M:test
```

This is the example for **Choreo**. The recipe it follows, step by step, is
[docs/choreo.md](../../docs/choreo.md).

## What is different about a Choreo spec

Two things, and both matter to a driver.

**All of the state is one variable.** Choreo keeps everything in `s`:

```
s = {system:     node -> {process_id, role, stage},
     messages:   node -> every message delivered to it,
     events:     node -> ...,
     extensions: whatever the spec adds}
```

**No transition has a name.** Under `quint run --mbt` every step is `"step"`,
and the picks are the node that acted and the outcome it chose. `quint test`
records no picks at all.

So the spec in [spec/two_phase_commit.qnt](spec/two_phase_commit.qnt) is
Choreo's with one addition: each transition also records which one it was,

```quint
effects: Set(choreo::Broadcast(CoordinatorCommit),
             record(DecidesOnCommit({ node: ctx.state.process_id }))),
```

and a custom effect writes that into `s.extensions.actionTaken`. That is the
same convention quint-connect (Rust) uses for its own two-phase commit. The
guards, post-states and messages are Choreo's, unchanged.

## The driver says where things are

```clojure
(q/defdriver two-phase-commit
  {:spec        "spec/two_phase_commit.qnt"
   :scan        '[tpc.core tpc.model-test]
   :state-path  [:s]                               ; compare s's fields
   :action-path [:extensions :actionTaken :tag]    ; the transition's name
   :nondet-path [:extensions :actionTaken :value]  ; its record: {:node "p1"}
   :ignore      #{:events}})                        ; type Event = ()
```

`:state-path [:s]` makes `:system`, `:messages`, `:events` and `:extensions`
the variables that are compared. The other two paths are read inside `s`, and
the variable they start at, `:extensions`, is the spec's bookkeeping and leaves
the comparison.

## The implementation is annotated with the transitions

```clojure
(defn decide-commit!
  "Commit once every participant has voted yes."
  {:quint/action "DecidesOnCommit"}
  [node]
  (when (and (= :working (stage node))
             (every? #(received? node [:prepared %]) participants))
    (stage! node :committed)
    (broadcast! :commit)))
```

The name is the variant's tag, and `node` binds by name to the one field of
its record. Nothing in [src/tpc/core.clj](src/tpc/core.clj) requires
quint-connect, and nothing in it knows Choreo's shapes: stages are `:working`
and `:committed`, messages are `:commit` and `[:prepared "p1"]`.

The translation to Choreo's shapes is two readers in the test namespace,
[test/tpc/model_test.clj](test/tpc/model_test.clj). A sum-type value decodes
to `{:tag "Working" :value []}`, and they build exactly that:

```clojure
(defn system {:quint/state :system} []
  (into {} (map (fn [[id {:keys [role stage]}]]
                  [id {:process_id id
                       :role       (variant (tag role))
                       :stage      (variant (tag stage))}]))
        @tpc/nodes))
```

## What random traces reach, and what they do not

Coverage is reported per transition, because the spec names them. It says
something worth knowing: a commit needs every participant to vote yes before
the coordinator gives up, and random traces rarely manage that. Ten seeds of
50 traces each reached `DecidesOnCommit` in four, once or twice each time.

So the commit path is written down in the spec as the run `commitTest`, and
replayed with `check-run`:

```clojure
(deftest the-commit-scenario-conforms-to-spec
  (qt/check-run two-phase-commit {:test "commitTest"}))
```

`quint test` writes no picks at all. It works anyway, because the transition
is recorded in `s`, and `s` is in every trace.

## Seeing it fail

The broken version ships with the example, so you do not have to edit anything:

```bash
clojure -M:test:broken
```

Those tests are tagged `^:broken`, excluded from `clojure -M:test`, and are
*supposed* to fail. The point is the message.

The first is the way two-phase commit blocks in real life: a participant that
voted yes waits for the commit, and ignores the abort that comes instead.

```
diverged at step 3, action "AbortsAsInstructed"
  picks    {:node "p3"}
  handler  #'tpc.core/handle-abort!
  readers  {:system #'tpc.model-test/system}
  ...
  in spec  {:system {"p3" {:stage {:tag "Aborted"}}}}
  in app   {:system {"p3" {:stage {:tag "Prepared"}}}}
```

The second is a coordinator that commits and forgets to say so, which
`commitTest` reaches every time; the diff is the message that was never sent:

```
diverged at step 4, action "DecidesOnCommit"
  picks    {:node "c"}
  handler  #'tpc.core/decide-commit!
  readers  {:messages #'tpc.model-test/messages}
  ...
  in spec  {:messages {"p3" #{{:tag "CoordinatorCommit", :value []}}, ...}}
  in app   nil
```

## Things that will surprise you

- **Most steps are repeats.** Recording a transition gives every transition an
  effect, and Choreo only drops transitions that change nothing. So a
  participant that has aborted can be told to abort again, and again: in the
  runs above, about three steps in four were `AbortsAsInstructed`. The
  implementation must treat a repeated instruction as the no-op it is — which
  one that receives a duplicate message must do anyway. If they get in the
  way, tighten the guard in the spec; this example leaves Choreo's guards
  alone.
- **A broadcast reaches the sender too.** Choreo's `Broadcast` delivers to
  every node, the one sending included, so the coordinator's own inbox holds
  its `CoordinatorAbort`. The network in `tpc.core` does the same.
- **`verify` does not run on a Choreo spec** with Quint 0.32.0 or 0.33.0:
  Apalache rejects every one, this one and Choreo's Tendermint alike, with an
  internal type-checking error. `check` and `check-run` are what there is.

## What to look at

- [spec/two_phase_commit.qnt](spec/two_phase_commit.qnt) — Choreo's spec, with
  the instrumentation marked. Choreo itself is vendored in
  [spec/choreo/](spec/choreo/), with its Apache-2.0 licence.
- [src/tpc/core.clj](src/tpc/core.clj) — the implementation, annotated with the
  transitions it implements, and no `:require` of quint-connect.
- [test/tpc/model_test.clj](test/tpc/model_test.clj) — two readers, one
  driver, two tests.

For the basics start at [../counter/](../counter/).
