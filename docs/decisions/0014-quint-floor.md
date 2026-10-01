# 0014 — Quint 0.33.0 is a floor, not a preference

Status: accepted. Supersedes the version policy of
[0004](0004-quint-cli.md) for versions older than the floor; newer ones are
still only warned about.

## Question

[0004](0004-quint-cli.md) says a Quint other than the tested one gets a warning,
not a failure: the fixtures record what one version wrote, and a format change
shows up as a failing decode test rather than a confusing runtime error.

That reasoning assumes a different version writes differently shaped traces,
which the decoder would reject loudly. Quint 0.32.0 writes traces that decode
perfectly and are wrong.

## What 0.32.0 does

Its rust evaluator — the default backend — records `mbt::actionTaken` and
`mbt::nondetPicks` first-write-wins. A sample that ends because `step` found
nothing enabled leaves its failed attempt's values in storage, and the next
sample's `init` cannot overwrite them. So state 0 of the next trace carries the
previous sample's action and picks. That is Quint's own account of it, in the
commit that fixed it for 0.33.0 (`5b7a850a`, Quint #2012):

> A sample that ends because `step` evaluated to false leaves the failed
> attempt's `action_taken` and `nondet_picks` in the storage. Since
> `track_action` is first-write-wins, the next sample's `init` could not
> relabel them.

Recorded here as `dev/fixtures/choreo/tpc_mislabel_0.itf.json`: Choreo's
two-phase commit, whose runs all end with every node decided, labels state 0
of 19 traces in 20 `"step"`. The typescript backend and 0.33.0 label all of
them `"init"`.

It is not a Choreo problem. Any spec whose samples can dead-end is exposed.
The label is the step action's own name: a five-line bank whose step is
`any { deposit, withdraw }` and which runs out of enabled actions labels state
0 of five traces in six `"step"` on 0.32.0, with a `Some` pick for `amount`,
and all six `"init"` on 0.33.0. The picks on that state are wrong for every
spec. The dispatch is wrong for a driver that handles the step action's name
itself — which is how Choreo as written is driven, since every step there is
`"step"`. Replay dispatches state 0 to `init` *unless a handler claims the
label*, so that handler is called instead of `init`, with picks no transition
used, before anything has been reset.

## Decision

- `quint/minimum-version` is `"0.33.0"`. The first command that runs Quint
  checks `quint --version` against it, and an older one is `:quint-too-old`,
  naming the reason and the upgrade command.
- `quint/tested-version` is `"0.33.0"` as well. A newer Quint is still only
  warned about, as 0004 decided: there is no evidence against it, only an
  absence of evidence for it.
- CI installs 0.33.0.
- Replay is untouched. A trace committed from 0.32.0 still replays; nothing
  runs Quint to replay, so there is nothing to check a version of. The one
  0.32.0 trace carrying the bug is kept as the recording that justifies this.

## Why not work around it

Teaching replay to send state 0 to `init` whatever its label says would fix
only the dispatch; the picks on that state would still be wrong, for anyone
reading them. And it would mean disbelieving the trace in exactly the case
where the trace is right — an `init` written as `any { a, b }` labels state 0
`"a"` or `"b"`, legitimately. The bug is Quint's, it is fixed, and the honest
fix here is to refuse the version that has it.

`--backend=typescript` avoids it on 0.32.0 and was the first workaround tried.
It is slower, and it asks every user to know about a bug they should never
meet.

## What changed on the way

Every fixture this repository records was re-recorded on 0.33.0 and compared
with its 0.32.0 recording, `#meta` timestamps aside: identical, except the one
state 0 the bug wrote. Apalache moved from 0.56.1 to 0.62.1 with it, and the
behaviour `verify` depends on — exit codes, where `_apalache-out/` lands, what a
counterexample contains — reproduced unchanged with
[`dev/probes/verify_probe.sh`](../../dev/probes/verify_probe.sh). One recorded
detail did change: Apalache now rejects an unknown key in `--apalache-config`.
Apalache 0.62.1 needs Java 21, which CI already uses.
