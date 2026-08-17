# 0008 — Releasing: tools.build, Clojars, and what "no new dependency" means

Status: accepted

## Question

[CLAUDE.md](../../CLAUDE.md) says no new dependency without an ADR, and that
the set is Clojure and `data.json`. Publishing a jar needs a build tool and a
deploy tool. Does the rule forbid them, and if not, why not?

## Decision

Two build-time dependencies, in a `:build` alias and nowhere else:

```clojure
io.github.clojure/tools.build {:mvn/version "0.10.9"}
slipset/deps-deploy           {:mvn/version "0.2.2"}
```

The artifact is `org.clojars.aldebogdanov/quint-connect`, source-only, EPL-2.0,
published to Clojars. `build.clj` holds the version and is the only place it
appears.

## Why this does not violate the rule

The rule protects **a consumer's classpath**. Its cost is what someone else
inherits by depending on this library: version conflicts they did not choose,
transitive surface they cannot see, and a `:test`-scoped tool that turns out to
drag something onto a production classpath.

Neither of these reaches a consumer. They live in an alias that is not part of
`:paths`, they are absent from the generated pom, and the published jar's
dependency list stays exactly two entries — Clojure and `data.json` — which is
verifiable by reading `META-INF/maven/.../pom.xml` inside the jar rather than
by trusting this paragraph.

So the ADR requirement is met by writing this down, and the dependency budget
is untouched. A build tool is not a dependency of the library; it is a
dependency of building the library.

## Why these two

`tools.build` is the official one, is a library rather than a framework, and
its API is data in and files out — the same shape the rest of this project
prefers. `deps-deploy` is thirty lines of wrapper over the Maven deploy
machinery and the standard choice for Clojars.

The alternative was Leiningen, which would have meant a `project.clj` beside
`deps.edn` and two descriptions of the same project. Two sources of truth about
the dependency set is exactly the failure this repository keeps arguing
against.

## No AOT

The jar carries `.clj` files and nothing compiled. A consumer compiles them,
which is the norm for a Clojure library and keeps the artifact independent of
the JVM and Clojure version it was built on. There is no `:gen-class` here and
no reason for one.

## Publishing is a human step

`bb deploy` reads `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` from the
environment, and the password must be a **deploy token**, not an account
password. A released version cannot be replaced — Clojars refuses to overwrite
a coordinate that exists — so the command is deliberately not wired into CI or
into any test task. Someone types it, having read what is about to go out.

## Consequences

- The version lives in `build.clj`. `CHANGELOG.md` says what is in it; a
  release is a commit that changes both, plus a `v<version>` git tag.
- `bb install` puts the jar in `~/.m2`, which is how a consumer coordinate gets
  tested before anything is published. That check is worth running: it is the
  difference between "the tests pass" and "the artifact works".
- If the dependency set of the *library* ever grows, that needs its own ADR.
  This one licenses build tooling only.
