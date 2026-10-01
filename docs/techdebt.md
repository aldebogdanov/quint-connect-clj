# Tech debt

Known gaps that are understood, written down, and not scheduled. Each entry
says what is missing, how it was found, and what it would take. Status is one
of **maybe** (worth doing if the cost comes down or a real user needs it),
**planned** or **won't**.

## Refusal checks — maybe

**What is missing.** Replay checks one direction: along every trace, the
implementation does what the spec did. It never checks that the implementation
*refuses* what the spec does not allow. An implementation that allows more than
the spec passes.

**How it was found.** Recorded on 2026-09-30 against the two-phase commit in
`dev/tpc/core.clj`: `give-up!` was redefined to abort a participant whatever
its stage — so one that had voted yes could abort on its own, a real safety
bug. `check` with `{:traces 50 :max-samples 500 :max-steps 20}` passed on seeds
1, 2 and 3. `SpontaneouslyAborts` was replayed 5 to 10 times per run, always on
a participant the spec allowed to abort, never on a prepared one, because no
trace ever asks that of it.

**What it would take.** Two shapes, neither cheap:

- *Refusal checks in replay.* The spec records, per state, which transitions
  are enabled — as a Choreo spec already records the one it took. At each step
  the driver also calls a transition the spec says is disabled, on a throwaway
  copy of the state, and expects nothing to change. Needs the spec to record
  enabledness, which Quint does not emit, and the application to be copyable
  or resettable to an arbitrary state, which the annotation contract does not
  ask of it.
- *Trace validation.* Record what the running system does, and ask whether the
  spec allows that behaviour. Checks what actually happened rather than what
  was sampled, and would catch the bug above if a recorded run hit it. Quint
  has no command for it; a recorded trace can be approximated as a Quint `run`
  and checked with `quint test`.

**Why maybe.** Either one is a design of its own, larger than a milestone, and
the first changes what the annotation contract asks of an application. Until
then the gap is stated where a user will meet it: the README, getting-started's
rough edges and architecture §11.
