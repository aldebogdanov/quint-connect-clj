# Contributing

Thanks for looking. This project has an unusual working agreement, and it is
worth two minutes before you write anything.

## The short version

This repository is written by people who intend to understand every line. That
goal outranks convention, habit and "best practice". A patch that adds power at
the cost of legibility will be turned down even when it is correct.

## Getting set up

```
bb test        # replay only
bb test:all    # everything except the Apalache tests
bb test:verify # the Apalache tests, minutes
```

The `bb` tasks are [babashka](https://babashka.org/), which is a convenience
and not a requirement — each one is a single `clojure` invocation, and running
them directly needs nothing but the `clojure` CLI:

```
clojure -M:test -e :integration -e :slow    # what `bb test` runs
clojure -M:test -e :slow                    # `bb test:all`
clojure -M:test -i :slow                    # `bb test:verify`
```

`bb test` is the one that must keep working on a bare machine: it runs entirely
on committed traces in `dev/fixtures/` and touches no external tool. If it
fails on a fresh clone with only Clojure installed, that is a bug — please
report it.

No test count is written here on purpose. It has been wrong twice.

`bb test:all` additionally shells out to the real `quint`
(`npm i -g @informalsystems/quint`). Those tests are tagged `^:integration`.
`bb test:verify` runs Apalache and takes minutes; those are `^:slow` and are
excluded from both of the above.

The projects under `examples/` depend on the **released** coordinate, so that
they can be copied out and run as-is. When you change the library, run them
against your working tree instead:

```
cd examples/counter && clojure -M:test:local
```

CI does exactly that. An example run without `:local` tests the last release
and says nothing about your commit.

## Before opening a pull request

- **One milestone, one change set.** See [docs/roadmap.md](docs/roadmap.md).
  No drive-by refactors of code outside what you came to change, and no
  reformatting of lines you did not touch.
- **Say what you verified, and how.** "Tests pass" means you ran the command
  and read the output. Claims about how Clojure or Quint behave get checked
  with a probe in `dev/probes/`, not recalled from memory. Several of the
  sharper facts in `docs/notes/` were discovered that way, and one of them was
  a correction to something we had already written down.
- **Fixtures are recordings.** Everything in `dev/fixtures/` is real output
  from a real tool run, never hand-written JSON that looks about right. If a
  case cannot be recorded, build it in the test by mutating a real fixture and
  say so in a comment.
- **Every bug fixed brings the trace that found it.**

## Design rules that will come up in review

These are the ones people trip on. The full set is in
[CLAUDE.md](CLAUDE.md), and the reasoning behind each is in
[docs/decisions/](docs/decisions/).

- **Data in, data out.** Public functions take a map and return a map. Side
  effects live in `quint` and `test` only.
- **No protocols, records or multimethods** until two real implementations
  exist here. A map of functions is the default.
- **No dynamic vars.** Configuration is an argument.
- **No new dependency without an ADR** in `docs/decisions/`. The current set is
  Clojure and `data.json`. Shrinking it is preferred to growing it.
- **Errors are `ex-info`** with a `:quint/error` keyword and enough data to act
  on — never a bare string, never a swallowed exception.
- **Reflection is confined to `registry`,** over the explicit `:scan` list.
  Never a classpath-wide scan, never a global registry atom.
- **The application must not be reshaped for the tool.** An annotated function
  keeps the signature it would have had anyway.
- A namespace approaching ~200 lines is a signal to stop and discuss, not to
  split reflexively.
- Prefer duplication over an abstraction used once.

## The annotation vocabulary is closed

`action`, `args`, `state`, `init`, `halt`. Adding a key needs a reason from a
real specification, not a hypothetical one. Anything that cannot be said in one
annotation on one var belongs in `:actions` or `:state` as a plain function.

## Proposing something larger

Open an issue first, especially for anything in
[docs/architecture.md](docs/architecture.md) §10, "Non-goals". If a change
alters a decision in `docs/decisions/`, it needs a new ADR that supersedes the
old one rather than an edit to it — see
[0007](docs/decisions/0007-annotation-keys.md) superseding
[0006](docs/decisions/0006-names.md) for the shape.

## License

By contributing you agree that your contributions are licensed under the
Eclipse Public License 2.0, the same as the rest of the project.
