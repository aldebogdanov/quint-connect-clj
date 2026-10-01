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
the coordinator gives up, and a random run manages that about once in fifty.

More steps would not help — a run ends when every node has decided, never
later than step 7. More *attempts* do. `:max-samples` is how many runs Quint
tries and `:traces` how many it writes, and it writes the longest. A commit is
the longest run this protocol has, so

```clojure
(qt/check two-phase-commit {:traces 50 :max-samples 500 :max-steps 20})
```

puts ten or eleven commits among the fifty traces, where leaving `:max-samples`
at 50 puts one. Measured over thirteen seeds, and every transition was
exercised in each; the test asserts that nothing in the coverage report is
left unused. Push it too far and the short runs are crowded out: at 2000
attempts, no written trace had a participant abort on its own.

And the one path that must always be tested is written down in the spec, as
the run `commitTest`, and replayed with `check-run`:

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

- **Recording a transition needs one more line to stay Choreo.** Choreo never
  removes a message, so an instruction stays in a participant's inbox after it
  has acted on it, and `choreo::step` drops the repeat only because it changes
  nothing. The record is an effect, so it would change something, and every
  repeat would be kept: without `changes_something` in the spec, three steps in
  four did nothing but repeat an instruction already acted on, and no run
  ended before `max-steps`. The filter counts the record out, and puts Choreo's rule back.
- **A broadcast reaches the sender too.** Choreo's `Broadcast` delivers to
  every node, the one sending included, so the coordinator's own inbox holds
  its `CoordinatorAbort`. The network in `tpc.core` does the same.
- **`verify` runs through TLC only, and replays nothing.** Apalache rejects
  every Choreo spec with an internal type-checking error — the cause is a
  state variable whose type is left generic until the module is instantiated,
  which is what Choreo's `s` is. `{:backend :tlc}` checks the spec: a property
  that holds passes, and one that does not says so, but Quint writes no trace
  from TLC, so there is no counterexample to replay here. The `^:slow` test in
  [test/tpc/model_test.clj](test/tpc/model_test.clj) checks the invariant
  `consistency` and the action property `commitIsFinal` — a participant that
  has committed stays committed — that way. It checks the spec, not the code
  in `src/`:

  ```clojure
  (qt/verify two-phase-commit {:temporal "commitIsFinal" :backend :tlc})
  ```

  `clojure -M:test -e :slow` leaves it out.

## What to look at

- [spec/two_phase_commit.qnt](spec/two_phase_commit.qnt) — Choreo's spec, with
  the instrumentation marked. Choreo itself is vendored in
  [spec/choreo/](spec/choreo/), with its Apache-2.0 licence.
- [src/tpc/core.clj](src/tpc/core.clj) — the implementation, annotated with the
  transitions it implements, and no `:require` of quint-connect.
- [test/tpc/model_test.clj](test/tpc/model_test.clj) — two readers, one
  driver, two tests.

For the basics start at [../counter/](../counter/).
