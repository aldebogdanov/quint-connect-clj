// Choreo's two-phase commit, instrumented so that every transition records
// which one it was. Derived from informalsystems/choreo at 000cf4e,
// examples/two_phase_commit/, Apache-2.0: see choreo/LICENSE. Changed: the
// definitions between the "instrumentation" markers, one record(...) effect on
// each transition, the initial extensions, what step and step_with hand to
// choreo::step and choreo::step_with -- the effect processor, and the filter
// that keeps Choreo's rule of dropping transitions that change nothing -- and
// the import paths. Every guard, post-state and message is Choreo's.
//
// Choreo never names a transition -- a trace carries only its outcome -- so an
// implementation cannot be driven by name without this. Each transition emits
// a RecordAction custom effect, and apply_custom_effects writes it into
// s.extensions.actionTaken: the variant's tag is the action name, its record
// the picks. That is the convention quint-connect (Rust) uses too.
module two_phase_commit {
  import basicSpells.* from "./choreo/spells/basicSpells"
  import choreo(processes = NODES) as choreo from "./choreo/choreo"

  // Auxiliary types
  type Role = Coordinator | Participant
  type Stage = Working | Prepared | Committed | Aborted

  // Mandatory types for the Choreo
  type Node = str
  type Message =
    | CoordinatorAbort
    | CoordinatorCommit
    | ParticipantPrepared(Node)

  type StateFields = {
    role: Role,
    stage: Stage,
  }

  // --- instrumentation -------------------------------------------------------
  type ActionTaken =
    | Init
    | SpontaneouslyPrepares({ node: Node })
    | SpontaneouslyAborts({ node: Node })
    | AbortsAsInstructed({ node: Node })
    | CommitsAsInstructed({ node: Node })
    | DecidesOnCommit({ node: Node })
    | DecidesOnAbort({ node: Node })

  type CustomEffects = RecordAction(ActionTaken)
  type Event = ()
  type Extensions = { actionTaken: ActionTaken }

  /* Boilerplate */
  type LocalState = choreo::LocalState[Node, StateFields]
  type LocalContext = choreo::LocalContext[Node, StateFields, Message, Event, Extensions]
  type Transition = choreo::Transition[Node, StateFields, Message, Event, CustomEffects]
  type GlobalContext = choreo::GlobalContext[
    Node,
    StateFields,
    Message,
    Event,
    Extensions
  ]
  /* End of boilerplate */

  pure def record(a: ActionTaken): choreo::Effect[Node, Message, Event, CustomEffects] =
    choreo::CustomEffect(RecordAction(a))

  pure def apply_custom_effects(ctx: GlobalContext, effect: CustomEffects): GlobalContext = {
    match effect {
      | RecordAction(a) => { ...ctx, extensions: { ...ctx.extensions, actionTaken: a } }
    }
  }

  // choreo::step drops a transition that changes nothing: no effects and the
  // same local state. The record is an effect, so without this every
  // transition would pass that test, and a node would be told again and again
  // to do what it already did. Each transition carries exactly one record, so
  // this is Choreo's own test with the record counted out.
  pure def changes_something(ctx: LocalContext, t: Transition): bool =
    t.effects.size() > 1 or t.post_state != ctx.state
  // --- end of instrumentation ------------------------------------------------

  pure def get_prepared_msgs(msgs: Set[Message]): Set[Node] = {
    msgs.filterMap(m => match m {
      | ParticipantPrepared(n) => Some(n)
      | _ => None
    })
  }

  // --- Participant transitions ----------------------------------------------
  pure def spontaneously_prepares(ctx: LocalContext): Set[Transition] = {
    if (ctx.state.role == Participant and ctx.state.stage == Working) {
      Set({
        effects: Set(choreo::Broadcast(ParticipantPrepared(ctx.state.process_id)),
                     record(SpontaneouslyPrepares({ node: ctx.state.process_id }))),
        post_state: { ...ctx.state, stage: Prepared }
      })
    } else {
      Set()
    }
  }

  pure def spontaneously_aborts(ctx: LocalContext): Set[Transition] = {
    if (ctx.state.role == Participant and ctx.state.stage == Working) {
      Set({
        effects: Set(record(SpontaneouslyAborts({ node: ctx.state.process_id }))),
        post_state: { ...ctx.state, stage: Aborted }
      })
    } else {
      Set()
    }
  }

  pure def aborts_as_instructed(ctx: LocalContext): Set[Transition] = {
    if (ctx.state.role == Participant and ctx.messages.contains(CoordinatorAbort)) {
      Set({
        effects: Set(record(AbortsAsInstructed({ node: ctx.state.process_id }))),
        post_state: { ...ctx.state, stage: Aborted }
      })
    } else {
      Set()
    }
  }

  pure def commits_as_instructed(ctx: LocalContext): Set[Transition] = {
    if (ctx.state.role == Participant and ctx.messages.contains(CoordinatorCommit)) {
      Set({
        effects: Set(record(CommitsAsInstructed({ node: ctx.state.process_id }))),
        post_state: { ...ctx.state, stage: Committed }
      })
    } else {
      Set()
    }
  }

  // --- Coordinator transitions ------------------------------
  pure def decides_on_commit(ctx: LocalContext): Set[Transition] = {
    if (ctx.state.role == Coordinator
        and ctx.state.stage == Working
        and ctx.messages.get_prepared_msgs().size() == PARTICIPANTS.size()) {
      Set({
        effects: Set(choreo::Broadcast(CoordinatorCommit),
                     record(DecidesOnCommit({ node: ctx.state.process_id }))),
        post_state: { ...ctx.state, stage: Committed }
      })
    } else {
      Set()
    }
  }

  pure def decides_on_abort(ctx: LocalContext): Set[Transition] = {
    if (ctx.state.role == Coordinator and ctx.state.stage == Working) {
      Set({
        effects: Set(choreo::Broadcast(CoordinatorAbort),
                     record(DecidesOnAbort({ node: ctx.state.process_id }))),
        post_state: { ...ctx.state, stage: Aborted }
      })
    } else {
      Set()
    }
  }

  pure def main_listener(ctx: LocalContext): Set[Transition] = {
    Set(
      spontaneously_prepares(ctx),
      spontaneously_aborts(ctx),
      aborts_as_instructed(ctx),
      commits_as_instructed(ctx),
      decides_on_commit(ctx),
      decides_on_abort(ctx)
    ).flatten()
  }

  pure val COORDINATOR: Node = "c"
  pure val PARTICIPANTS: Set[Node] = Set("p1", "p2", "p3")
  pure val NODES = PARTICIPANTS.union(Set(COORDINATOR))

  pure def initialize(n: Node): LocalState = {
    {
      process_id: n,
      role: if (n == COORDINATOR) Coordinator else Participant,
      stage: Working,
    }
  }

  // Setting up the state machine

  action init = choreo::init({
    system: NODES.mapBy(n => initialize(n)),
    messages: NODES.mapBy(n => Set()),
    events: NODES.mapBy(n => Set()),
    extensions: { actionTaken: Init }
  })

  action step = choreo::step(
    ctx => main_listener(ctx).filter(t => changes_something(ctx, t)),
    apply_custom_effects
  )

  // Invariants and witnesses

  val consistency = PARTICIPANTS.forall(p1 => {
    PARTICIPANTS.forall(p2 => {
      not(choreo::s.system.get(p1).stage == Committed
        and choreo::s.system.get(p2).stage == Aborted)
    })
  })

  val wit_commit = PARTICIPANTS.exists(p => choreo::s.system.get(p).stage != Committed)

  action step_with(v: Node, listener: LocalContext => Set[Transition]): bool =
    choreo::step_with(v, ctx => listener(ctx).filter(t => changes_something(ctx, t)),
                      apply_custom_effects)

  run commitTest = init
    .then(step_with("p1", spontaneously_prepares))
    .then(step_with("p2", spontaneously_prepares))
    .then(step_with("p3", spontaneously_prepares))
    .then(step_with(COORDINATOR, decides_on_commit))
    .then(step_with("p1", commits_as_instructed))
    .then(step_with("p2", commits_as_instructed))
    .then(step_with("p3", commits_as_instructed))
    .expect({
      // Check that all participants have committed
      PARTICIPANTS.forall(p => {
        choreo::s.system.get(p).stage == Committed
      })
    })
}
