# 0012 — Vendor Choreo's Quint files, twice, and nothing into the jar

Status: accepted

## Question

Testing Choreo support needs Choreo. A two-phase commit spec written with it
imports `choreo.qnt`, which imports `spells/basicSpells.qnt`, and Quint resolves
both from the file system, relative to the importing file. There is no package
manager for Quint to fetch them with.

CLAUDE.md says no new dependency without an ADR. These files never reach the
classpath, but they are third-party code that this repository would carry,
update and answer for, and that is what the rule is about.

## Decision

Copy them in, at a pinned commit, with the licence beside them.

- **What**: `choreo.qnt` and `spells/basicSpells.qnt` from
  [informalsystems/choreo](https://github.com/informalsystems/choreo) at
  `000cf4eed315187dc6f216a148781cff7dde6521` (2026-06-23), byte for byte, and
  Choreo's `LICENSE`. About 21 KB. `rareSpells.qnt` is not taken: nothing here
  imports it.
- **Where**: twice. `dev/fixtures/choreo/` for the recordings this library is
  tested against, and `examples/two-phase-commit/spec/choreo/` for the example.
  Every example is a self-contained project that can be copied out of this
  repository and run, and one that reached into `dev/` would stop being that.
  Two copies of 21 KB is the cheaper cost; CLAUDE.md prefers duplication to an
  abstraction, and a shared directory both would point into is one.
- **Choreo's own `two_phase_commit.qnt`** is vendored into `dev/fixtures/choreo/`
  with its two import lines changed to point beside it, and a header saying so.
  Nothing else in it differs. The instrumented version is ours, is derived from
  it, and says that at the top.
- **Not in the jar.** `build.clj` packages `src` and `resources`; neither
  directory holds any of this. A consumer gets no Quint files from depending on
  the library, and a Choreo user already has Choreo.

## Licence

Choreo is Apache-2.0. This project is EPL-2.0. Carrying Apache-2.0 files
alongside EPL-2.0 code is permitted; what Apache-2.0 asks is a copy of the
licence with the files (§4a), a prominent notice on any file that was modified
(§4b), and that attribution notices are kept (§4c). Choreo ships no `NOTICE`
file, so §4d asks for nothing more. Hence: `LICENSE` in each vendored
directory, a `README.md` there naming the source and the commit, and a header
on each modified or derived `.qnt` file.

## Updating

By hand, deliberately, like the Quint version: copy the files at a new commit,
update the commit in both `README.md`s, re-record the fixtures with
`bb fixtures`, and read the diff. A Choreo change that alters what a trace looks
like is exactly what the recordings exist to show.

## Alternatives rejected

- **A git submodule.** A second checkout step for every contributor and every
  CI job, for 21 KB, and a copied-out example would lose it.
- **Fetching at test time.** `bb test` must run on a bare machine with no
  network — that is the claim the two-phase split makes — and fetching at
  generation time only would make the fixtures' source unreproducible.
- **Writing a Choreo-shaped spec ourselves.** Testing against an imitation of
  the framework proves the imitation works.
