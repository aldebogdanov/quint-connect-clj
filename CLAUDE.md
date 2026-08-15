# Working agreement

This repository is written by a human who intends to understand every line.
The rules below exist to keep that possible. They outrank habit, convention and
"best practice".

## Process

- Work one milestone at a time, in the order given in
  [docs/roadmap.md](docs/roadmap.md). Do not start the next one unopposed.
- Before writing code for a milestone: state the plan, the namespaces touched,
  the public functions and their signatures. Wait for agreement.
- One change set = one milestone = one review. No drive-by refactors of code
  outside the milestone, no reformatting of untouched lines.
- If a milestone turns out to need something not in the plan, stop and say so
  instead of expanding the diff.
- Report what was actually verified, and how. "Tests pass" means the command
  was run and its output was read. Claims about how Clojure or Quint behave get
  checked with a probe in `dev/probes/`, not recalled.

## Code

Namespaces are written below by their last segment; all live under
`uno.michelada.quint-connect.*` (Clojars: `uno.michelada/quint-connect`).

- **Data in, data out.** Public functions take a map and return a map.
  Side effects live in `quint` and `test` only.
- **Reflection is confined to `registry`**, and only over the
  explicit `:scan` list of namespaces. Never a classpath-wide scan, never a
  global registry atom, never cached between runs. Everything downstream of the
  registry consumes a plain driver map and must work without metadata at all.
- **No macro without a function underneath.** `defdriver` is `(def name (driver
  m))` and nothing more. The map form stays supported forever.
- **No protocols, records, multimethods or `defmulti`** until two real
  implementations exist in this repository. A map of functions is the default.
- **No dynamic vars.** Configuration is passed as an argument.
- **No new dependency** without an ADR in `docs/decisions/`. The current set is
  Clojure and `data.json`, and shrinking it is preferred to growing it.
- **Errors are `ex-info`** with a `:quint/error` keyword and enough data to
  act on. Never a bare string, never a bare `assert`, never a swallowed
  exception. This key is fixed; `:key-ns` does not move it.
- **Clojure on the JVM only.** `.clj` files, no reader conditionals, no
  `#?(:clj ...)` "just in case".
- Prefer duplication over an abstraction that has been used once.
- A namespace approaching ~200 lines is a signal to stop and discuss, not to
  split reflexively.
- Docstrings on every public var: what it takes, what it returns, what it
  throws. No docstrings that restate the name.
- Comments explain *why*. Delete commented-out code.

## The annotation contract

`action`, `args`, `state`, `init`, `halt` — written `:quint/action` and needing
no `require`. That is the whole vocabulary; adding a key needs a reason from a
real spec, not a hypothetical one.

- **One spelling per driver.** `quint` is the default qualifier; a driver may
  move all five keys at once with `:key-ns`, a symbol. No per-key override, no
  second accepted form within one driver, and one place in the registry that
  knows the qualifier. See
  [docs/decisions/0007-annotation-keys.md](docs/decisions/0007-annotation-keys.md).
- **The application must not be reshaped for the tool.** No context parameter,
  no return-value convention, no protocol. An annotated function keeps the
  signature it would have had anyway; a handler's return value is ignored; state
  is read from declared readers, never accumulated.
- Annotations are otherwise inert, and they are free: an annotated namespace
  takes on no `:require` and no dependency. This library belongs in a `:test`
  alias, and nothing in application code ever calls it.
- Adaptation belongs in the test namespace: a getter that reshapes state, or a
  wrapper around a function whose real arguments are not picks. Both are
  annotated the same way as application code.
- Anything that cannot be said in one annotation on one var belongs in
  `:actions` or `:state` as a plain function. Never grow the annotations into a
  DSL.
- Fail loudly at driver construction, never at step 12 of trace 7. Silence is
  the failure mode this design is most exposed to — an unread annotation must
  always become an error, and the message must name the likely cause. There is
  one accepted exception, and it is written down rather than forgotten: an
  annotation left under the wrong `:key-ns` in a namespace that scans non-empty
  is not detected. See
  [docs/decisions/0007-annotation-keys.md](docs/decisions/0007-annotation-keys.md)
  §"The gap this leaves". Do not close it with a namespace-similarity
  heuristic; widen `:empty-scan` or leave it.
- The `^{...} (defn ...)` trap is silent in Clojure itself. Keep it covered by a
  test and named in the error text.
- State readers are called once per step: they must be cheap and must not
  disturb what they observe. `:quint/init` runs once per trace, and the
  step-0 comparison is what proves it reset everything.

## Naming

- kebab-case; `!` for side-effecting functions; `->` for conversions
  (`itf->trace`); predicates end in `?`.
- Keys from Quint keep Quint's spelling (`:lastError`, not `:last-error`).
  Round-tripping to the spec matters more than Clojure aesthetics.

## Tests

- Every decoding rule, every validation error and every failure mode gets a
  fixture in `dev/fixtures/`.
- Fixtures are recordings of real tool output, never hand-written JSON that
  "looks right".
- Every bug fixed gets the trace that found it, committed.
- `bb test` is the command.

## Out of scope, permanently

Parsing Quint in Clojure. Generating specs from code. Guessing which function
implements an action without an annotation. Type declarations in annotations. A
DSL for multi-step actions. A bespoke test runner. ClojureScript. See
[docs/architecture.md](docs/architecture.md) §9.
