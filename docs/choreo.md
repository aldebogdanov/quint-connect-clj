# Testing an implementation against a Choreo spec

[Choreo](https://github.com/informalsystems/choreo) is a Quint framework for
distributed protocols. This page is the recipe for driving a Clojure
implementation from a Choreo spec: follow the steps in order, and each one says
what you should have when it is done. It is written to be followed by a person
or by an agent.

A complete, runnable instance of everything here is
[examples/two-phase-commit/](../examples/two-phase-commit/). If you are new to
this library, [getting-started.md](getting-started.md) comes first.

## What is different about a Choreo spec

Two things, and the recipe exists because of them.

1. **All of the state is one variable, `s`**:

   ```
   s = {system:     node -> local state, which repeats the node's id,
        messages:   node -> every message delivered to it (never removed),
        events:     node -> events,
        extensions: whatever the spec adds}
   ```

2. **No transition has a name.** Under `quint run --mbt`, `mbt::actionTaken` is
   `"step"` on every step, and the picks are the node that acted (`v`) and the
   outcome it chose (`transition`, which is `{post_state, effects}`). Under
   `quint test`, there are no picks at all.

So the spec records each transition itself, in `s.extensions`, and the driver
is told where to find it. That is also how quint-connect (Rust) tests Choreo
specs. The reasoning is in
[decisions/0013-choreo-state-path.md](decisions/0013-choreo-state-path.md).

## Step 1 — Record each transition in the spec

Four additions, none of which touch the protocol's logic. In Quint:

```quint
// (a) Name every transition, with the picks the implementation needs as a
//     record. The tag becomes the action name; the record's fields become the
//     names the implementation's parameters bind to.
type ActionTaken =
  | Init
  | SpontaneouslyPrepares({ node: Node })
  | DecidesOnCommit({ node: Node })
  // ... one variant per transition

// (b) A custom effect that records it, and a place in the extensions for it.
type CustomEffects = RecordAction(ActionTaken)
type Extensions = { actionTaken: ActionTaken }

pure def record(a: ActionTaken): choreo::Effect[Node, Message, Event, CustomEffects] =
  choreo::CustomEffect(RecordAction(a))

pure def apply_custom_effects(ctx: GlobalContext, effect: CustomEffects): GlobalContext = {
  match effect {
    | RecordAction(a) => { ...ctx, extensions: { ...ctx.extensions, actionTaken: a } }
  }
}

// (c) Every transition adds one effect, wherever a Transition is built —
//     in a listener, or in the upon-function of a choreo::cue.
{
  effects: Set(choreo::Broadcast(CoordinatorCommit),
               record(DecidesOnCommit({ node: ctx.state.process_id }))),
  post_state: { ...ctx.state, stage: Committed }
}

// (d) The initial value, and the processor handed to Choreo.
action init = choreo::init({ ..., extensions: { actionTaken: Init } })
action step = choreo::step(main_listener, apply_custom_effects)
```

If the spec already has extensions, add an `actionTaken` field to its record
rather than replacing it. If it already has custom effects, add `RecordAction`
as one more variant and one more `match` arm. Hand `apply_custom_effects` to
every `choreo::step…` call, `step_with` in `run`s included.

A variant's value must be a **record**, even for one pick: write
`Prepares({ node: Node })`, not `Prepares(Node)`. A variant with no argument,
like `Init`, is fine: it means no picks.

**Done when** `quint run spec.qnt --mbt --max-steps=5 --out-itf=t.itf.json`
writes a trace in which every state's `s.extensions.actionTaken` has a `tag`
naming the transition that produced it.

## Step 2 — The driver map

```clojure
(q/defdriver two-phase-commit
  {:spec        "spec/two_phase_commit.qnt"
   :scan        '[tpc.core tpc.model-test]
   :state-path  [:s]
   :action-path [:extensions :actionTaken :tag]
   :nondet-path [:extensions :actionTaken :value]
   :ignore      #{:events}})
```

- `:state-path [:s]` makes the fields of `s` the variables that are compared:
  `:system`, `:messages`, `:events`, `:extensions`. The name is the spec
  variable's last `::` segment, so `two_phase_commit::choreo::s` is `:s`.
- `:action-path` and `:nondet-path` are read **inside** `s`. The field they
  start at, `:extensions`, is the spec's bookkeeping and is not compared.
- `:ignore` names what the implementation has no counterpart for. With
  `type Event = ()` nothing ever happens in `events`, so there is nothing to
  supply.

**Done when** decoding your trace with those three paths names every step,
which needs nothing annotated yet:

```clojure
(require '[org.clojars.aldebogdanov.quint-connect.itf :as itf])

(->> (itf/itf->trace (itf/json->itf (slurp "t.itf.json"))
                     {:state-path  [:s]
                      :action-path [:extensions :actionTaken :tag]
                      :nondet-path [:extensions :actionTaken :value]})
     :states
     (map (juxt :action :picks)))
;; => (["Init" {}] ["DecidesOnAbort" {:node "c"}] ["SpontaneouslyAborts" {:node "p1"}] ...)
```

## Step 3 — Annotate the operations

Each transition is one function in the implementation, annotated with the
variant's tag. Its parameters are named after the record's fields:

```clojure
(defn decide-commit! {:quint/action "DecidesOnCommit"} [node] ...)
(defn prepare!       {:quint/action "SpontaneouslyPrepares"} [node] ...)
(defn start!         {:quint/init true} [] ...)          ; the Init step
```

- The initial state is `Init`, and it goes to `:quint/init`. Do not annotate an
  action `"Init"`.
- A parameter named differently from the field needs `:quint/args`:
  `(defn decide! {:quint/action "DecidesOnCommit" :quint/args [:node]} [coordinator-id] ...)`.
- An operation whose real arguments are not the picks — it needs the whole
  cluster, say — gets a one-line wrapper in the test namespace, annotated the
  same way.
- The return value is ignored. State is read afterwards, in step 4.
- Until step 4 puts readers in the test namespace, leave it out of `:scan`: a
  scanned namespace with no annotations in it is `:empty-scan`.

**Done when** `(q/replay-file two-phase-commit "t.itf.json")` no longer throws
`:no-init` or `:unknown-action`. It returns a result, most likely a divergence
at step 0: nothing reads the state back yet.

## Step 4 — Read the state back in Choreo's shapes

One reader per field of `s` you compare, in the test namespace. The spec's
values decode like this:

| Quint                              | decodes to                                   |
| ---------------------------------- | -------------------------------------------- |
| `Working` (variant, no argument)   | `{:tag "Working" :value []}`                 |
| `ParticipantPrepared("p1")`        | `{:tag "ParticipantPrepared" :value "p1"}`   |
| `{ process_id: "c", stage: ... }`  | `{:process_id "c" :stage ...}`               |
| `Map("c" -> ...)`, `mapBy`         | `{"c" ...}` — string keys stay strings       |
| `Set(...)`                         | `#{...}`                                     |
| `()`                               | `[]`                                         |

So the readers translate the implementation's own vocabulary into that:

```clojure
(defn- variant
  ([tag] {:tag tag :value []})
  ([tag v] {:tag tag :value v}))

(defn system {:quint/state :system} []
  (into {} (map (fn [[id {:keys [role stage]}]]
                  [id {:process_id id
                       :role       (variant (str/capitalize (name role)))
                       :stage      (variant (str/capitalize (name stage)))}]))
        @tpc/nodes))

(defn- message [m]                      ; :abort, :commit, [:prepared "p1"]
  (case m
    :abort  (variant "CoordinatorAbort")
    :commit (variant "CoordinatorCommit")
    (variant "ParticipantPrepared" (second m))))

(defn messages {:quint/state :messages} []
  (update-vals @tpc/inboxes #(into #{} (map message) %)))
```

A namespace that scans itself sees only what it has defined so far: put the
readers **above** the `defdriver`.

Readers are called once per step. They must be cheap and must not change what
they read.

**Done when** `q/replay-file` on your trace returns `:ok? true`.

## Step 5 — The tests

```clojure
(deftest conforms-to-spec
  (qt/check two-phase-commit {:traces 50 :max-steps 20}))

(deftest the-commit-scenario-conforms
  (qt/check-run two-phase-commit {:test "commitTest"}))    ; a `run` in the spec
```

`check-run` works although `quint test` writes no picks, because the
transition is recorded in `s`, and `s` is in every trace.

Read the coverage in the result: `(get-in r [:coverage :unused])` lists
transitions no trace exercised. Protocols often have a path random traces
rarely reach — in the two-phase commit, a commit needs every participant to
vote yes before the coordinator gives up — and a `run` in the spec is how to
make sure it is tested.

## When it fails

| you see                                                                                | it means                                                               | do this                                                      |
| -------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- | ------------------------------------------------------------ |
| `:bad-decode-path` "… at :s, which is every variable … set :state-path [:s]"           | the paths are rooted at `s`, which would remove all of the state        | add `:state-path [:s]`, and drop `:s` from the other paths    |
| `:bad-decode-path` ":state-path [:s] found nothing in state 0; the trace's variables are …" | the variable has another name                                     | use one of the names listed                                   |
| `:bad-decode-path` ":action-path [:extensions :actionTaken :tag] found nothing …"      | the spec does not record transitions, or the field is named otherwise  | step 1, or fix the path                                        |
| `:bad-decode-path` ":nondet-path … found \"p1\" …, and picks must be a record"         | a variant carries a bare value                                          | wrap it in a record: `Prepares({ node: Node })`               |
| `:unknown-action` "no handler for action \"DecidesOnAbort\""                           | a transition with no annotated operation                               | step 3                                                        |
| `:bad-arglist` "… takes a parameter named for the pick :id …"                          | a parameter is not named after a field of the record                    | rename it, or `:quint/args`                                   |
| a divergence at step 0 on `:events` or `:extensions`                                   | a field nothing supplies                                                | supply it, or `:ignore` it                                    |
| a divergence on `:messages` right after a broadcast                                    | Choreo's `Broadcast` delivers to the sender too                         | deliver to every node, sender included                         |

Two things are not errors, and will still surprise you:

- **Repeated instructions.** Recording gives every transition an effect, and
  Choreo drops only transitions that change nothing, so an instruction can be
  delivered again to a node that has already acted on it. In the two-phase
  commit, about three steps in four change nothing at all: a participant told
  again to do what it already did. The implementation must treat it as the
  no-op it is. If the repeats get in the way, tighten the transition's guard in
  the spec.
- **`display`**, if the spec uses `step_with_displayer`, is outside `s` and is
  not compared.

## Without instrumenting the spec

When the spec is not yours to change, Choreo as written still replays under
`check`, through one handler for `"step"` that maps an outcome back to an
operation:

```clojure
(defn- step [{:keys [v transition]}]
  (let [{:keys [role stage]} (:post_state transition)]
    (case [(:tag role) (:tag stage)]
      ["Participant" "Prepared"]  (tpc/prepare! v)
      ["Coordinator" "Committed"] (tpc/decide-commit! v)
      ...)))

(q/defdriver as-written
  {:spec       "spec/two_phase_commit.qnt"
   :scan       '[tpc.core tpc.model-test]
   :state-path [:s]
   :actions    {"step" step}
   :ignore     #{:events :extensions}})
```

What it costs:

- **Only `check`.** `quint test` writes no picks, so there is nothing for
  `"step"` to take apart.
- **Coverage says `"step"`**, and nothing about which transitions ran.
- **Guessing.** Two transitions can have the same outcome — a participant that
  aborts on its own and one told to abort both end `Aborted` with no effects —
  and the handler has to pick one. An instrumented spec knows which it took.
- **Quint 0.33.0, at least** — which the library requires anyway. 0.32.0 could
  label state 0 `"step"`, and a driver that handles `"step"` would then be
  handed the initial state before `init` had run. See
  [0014](decisions/0014-quint-floor.md).

## What does not work

**`verify`.** With Quint 0.32.0 and 0.33.0, `quint verify` fails on every
Choreo spec — instrumented or not, the two-phase commit and Choreo's Tendermint
alike — with Apalache reporting an internal type-checking error. `q/verify`
reports it as `:quint-failed` with that output verbatim. There is no
counterexample to replay until that is fixed upstream.

## Reference

- [examples/two-phase-commit/](../examples/two-phase-commit/) — all of the
  above, runnable, with a broken implementation to show what failure reads
  like.
- [decisions/0013-choreo-state-path.md](decisions/0013-choreo-state-path.md) —
  why the spec records its transitions, and why `:state-path`.
- [decisions/0012-vendor-choreo.md](decisions/0012-vendor-choreo.md) — why
  Choreo is copied into this repository, and its licence.
- [notes/itf-format.md](notes/itf-format.md) §Choreo — what Quint actually
  writes for a Choreo spec, recorded.
- [`dev/probes/choreo_probe.sh`](../dev/probes/choreo_probe.sh) — re-checks,
  against the `quint` on your `PATH`, what this page says the two-phase commit
  looks like in a trace, the repeats, the mislabelled state 0, and `verify`.
