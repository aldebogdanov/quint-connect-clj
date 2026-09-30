# Choreo, vendored

[Choreo](https://github.com/informalsystems/choreo) is Apache-2.0, not EPL-2.0
like the rest of this repository; its licence is [LICENSE](LICENSE), beside
these files. Why it is copied in rather than fetched, and how to update it, is
[docs/decisions/0012-vendor-choreo.md](../../../docs/decisions/0012-vendor-choreo.md).

Taken from commit `000cf4eed315187dc6f216a148781cff7dde6521` (2026-06-23).

| file | provenance |
| ---- | ---------- |
| `choreo.qnt` | Choreo's, byte for byte |
| `spells/basicSpells.qnt` | Choreo's, byte for byte |
| `LICENSE` | Choreo's, byte for byte |
| `two_phase_commit.qnt` | Choreo's `examples/two_phase_commit/two_phase_commit.qnt`, with its two import paths pointed beside it and a header saying so |
| `two_phase_commit_tracked.qnt` | ours, derived from the above: every transition records which one it was in `s.extensions.actionTaken`, and `step` and `step_with` keep Choreo's rule of dropping transitions that change nothing. Protocol logic unchanged |

## Recordings

Quint 0.32.0, rust evaluator, and identical on 0.33.0 apart from `#meta`
timestamps — except the mislabel pair, which is why 0.33.0 is the floor. The
two `tpc_tracked_*` files were recorded again on 0.33.0 when the instrumented
spec gained its no-op filter. `bb
fixtures` regenerates all but that pair, which 0.33.0 cannot reproduce.

| file | what it is |
| ---- | ---------- |
| `tpc_run_0.itf.json` | Choreo as written, `quint run --mbt`, seed 42: `mbt::actionTaken` is `"step"` on every step, and the picks are a node and an outcome |
| `tpc_tracked_run_0.itf.json` | instrumented, `quint run --mbt`, seed 42: carries both `mbt::` and the recorded transition, and ends at step 4, when every node has decided |
| `tpc_tracked_test_commitTest.itf.json` | instrumented, `quint test` of `commitTest`: no `mbt::` at all, and every transition named |
| `tpc_mislabel_0/1.itf.json` | Choreo as written, `quint run --mbt`, seed 42, two traces: state 0 of trace 0 says `"step"` with picks `{v: Some("p1"), transition: None}`, and state 0 of trace 1 says `"init"`. Quint 0.32.0's rust evaluator leaving a dead-ended sample's metadata for the next `init` (Quint #2012). The typescript backend and Quint 0.33.0 write `"init"` in both. Kept as the recording behind [0014](../../../docs/decisions/0014-quint-floor.md), and not regenerated |

What these show, and what `quint verify` does with the same specs, is
reproducible with [dev/probes/choreo_probe.sh](../../probes/choreo_probe.sh).
