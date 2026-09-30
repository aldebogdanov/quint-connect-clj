# 0013 — `:state-path`, and a Choreo spec records its own transitions

Status: accepted

## Question

A Choreo spec keeps all of its state in one variable, `s`:

```
s = {system: Node -> LocalState, messages: Node -> Set[Message],
     events: Node -> Set[Event], extensions: ...}
```

and names no transition. `mbt::actionTaken` is `"step"` on every step, and the
picks are an outcome — the acting node and `{post_state, effects}` — with no
name in them. `quint test` and `quint verify` emit no picks at all. The
recordings are in [roadmap.md](../roadmap.md) §M9.

How does an implementation get driven by one?

## Decision

**The spec records the transition, the way quint-connect (Rust) already does.**
Each transition emits one more effect, and the effect processor writes it into
the extensions:

```quint
type ActionTaken = Init | DecidesOnCommit({ node: Node }) | ...
type CustomEffects = RecordAction(ActionTaken)
type Extensions = { actionTaken: ActionTaken }
```

The variant's tag is the action name, its record is the picks. It is state, so
it is in every trace every command writes.

**The library reads it with the paths it already has, plus one:**

```clojure
{:state-path  [:s]
 :action-path [:extensions :actionTaken :tag]
 :nondet-path [:extensions :actionTaken :value]}
```

`:state-path` says where the state is. The compared state becomes the record
found there, and its fields stand in for spec variables — `:system`,
`:messages`, `:events`, `:extensions` — so a reader is annotated
`{:quint/state :system}` exactly as it would be for a spec with a variable of
that name. The other two paths are read inside it, and each root leaves the
compared state as it always has: here that removes `:extensions`, which is the
bookkeeping and nothing else.

Two smaller rules come with it:

- **`[]` at `:nondet-path` is no picks.** A variant without an argument —
  `Init` — carries the empty tuple. It is Quint's spelling of "nothing", not a
  malformed record. Anything else that is not a record is still refused.
- **Paths that leave nothing to compare are an error.** Without `:state-path`,
  `:action-path [:s :extensions :actionTaken :tag]` reads the right action and
  then removes `s` — all of the state. Every step then compares nothing:
  replayed through driver-map handlers that do nothing, the recorded
  `commitTest` passes all eight steps. That is a green test that tests nothing,
  reached by writing the paths the obvious way, so decoding refuses it and
  names `:state-path`. Accepting the empty tuple above would have widened the
  hole — with `:nondet-path` set too, the old code failed only on `Init` —
  which is why the two ship together.

## Why this, and not the alternatives

**A function where a path goes.** The roadmap entry this replaces suggested
letting `:action-path` take a function of the decoded step, so a test namespace
could classify an outcome into a name. It would work, and it would put the
knowledge of which transition produced which outcome into Clojure, where
nothing checks it against the spec. Two transitions with the same outcome —
`spontaneously_aborts` and `aborts_as_instructed` both yield a participant
`Aborted` with no effects — cannot be told apart from the outcome at all. The
spec knows which one it took; recording it there is the only place that is
not a guess. It also reaches `quint test` traces, which a classifier over picks
never could, because there are no picks in them.

**A preset**, `{:choreo true}`. It would hard-code `s`, `extensions` and
`actionTaken`, which are a convention and not Choreo's API: the module could be
instantiated under another name, and the field is the spec author's to name.
Three explicit keys are three lines, and each one is an ordinary option that
means the same thing outside Choreo.

**Absolute paths** for `:action-path` under `:state-path`. Then the root of
`[:s :extensions ...]` is `s` again, and the rule "a path's root leaves the
state" would need an exception for it. Reading the paths inside the state they
describe keeps the rule as it was.

**Comparing only `system`**, as quint-connect (Rust) does with
`state: [.., "system"]`. `{:state-path [:s :system]}` does that, and is
allowed. It is not what the example recommends: `messages` is where a
coordinator that broadcasts to the wrong nodes shows up, and dropping it should
be a choice made with `:ignore`, visibly, not a default.

## The route that needs no instrumentation

A spec as Choreo wrote it still replays under `check`, with `:state-path [:s]`
and one driver-map entry for `"step"` that maps an outcome back to an
operation. That is supported and tested, and it is the right first step when
the spec is not yours to change. It reaches nothing but `check`, and coverage
can only say `"step"`.

## Consequences

- An instrumented spec is a changed spec: every transition now has an effect,
  so Choreo's filter no longer drops no-op transitions, and a node is told
  again and again to do what it already did — three steps in four, in the
  two-phase commit. The spec puts the rule back with one more filter,
  `changes_something`, which is Choreo's test with the record counted out. It
  is part of the recipe, not an option: without it no trace ends before
  `max-steps`, and `:max-samples`, which picks the longest traces, has nothing
  to choose between.
- `verify` cannot replay a Choreo counterexample. Apalache rejects every
  Choreo spec — a state variable whose type stays generic until instantiation,
  reduced in [notes/itf-format.md](../notes/itf-format.md) §Choreo — and TLC,
  which checks them, writes no trace. The instrumented route asks nothing of a
  counterexample that a `quint test` trace does not already satisfy, but that
  is inference until one exists.
- `itf` grows, and was already past the ~200 lines CLAUDE.md treats as a
  signal. [architecture.md](../architecture.md) §3 says where it stands.
