# 0011 — `:quint/args` composes any shape, and the paragraph that said it would not

Status: accepted. Supersedes the *grammar* decided in
[0010-args-shapes.md](0010-args-shapes.md); everything else that ADR settled —
why the map form exists at all, the validation it bought, the `_` rule, the
alternatives it rejected — stands unchanged.

## Question

[0010](0010-args-shapes.md) let a `:quint/args` entry be a map, so that a
handler whose own signature takes a map could be annotated where it stands. It
drew the line there, in as many words:

> One branch, not nested, no operators. **If a later change wants a second
> branch, this paragraph is the one to argue against.**

A later change wants a second branch. Handlers destructure vectors too:

```clojure
(defn move {:quint/action "move"} [[x y]] ...)      ; spec: nondet x, nondet y
```

and they nest — a vector inside a map, a map inside a vector, either inside
either, as deep as the parameter list goes.

## The argument against, made and lost

The case is thinner than the map case was. A Clojure function taking
`{:keys [...]}` is everywhere; one taking a single positional `[[x y]]` is
unusual, because you would normally write `[x y]` and be on the plain path
already. And a *nested* one is rarer still.

It is also thinner than it first appears, because one shape people reach for
is already covered. Recorded, not reasoned:

```
nondet p = Set((1, 2), (3, 4)).oneOf()
"mbt::nondetPicks": {"p": {"tag": "Some", "value": {"#tup": [1, 2]}}}
```

A Quint tuple decodes to a Clojure vector, so `:quint/args [:p]` against
`[[x y]]` destructures it already, exactly as a record pick does. What is left
uncovered is only *building* a vector out of several separate picks.

What decided it anyway was not the use case. It was that the general rule is
**shorter than the special one**:

| 0010 | this |
| ---- | ---- |
| an entry is a pick name, or a map whose keys are what the handler destructures and whose values are pick names | an entry is a pick name, or a vector or map of entries |

and that it collapses to a single sentence anyone can hold:
**write the shape of the argument, with pick names where its values go.**

## Decision

An entry in `:quint/args` is a **template**, and templates nest:

- a **keyword** is a leaf — the pick of that name;
- a **vector** composes a vector of templates;
- a **map** composes a map with those keys, its values being templates.

`:quint/args` itself stays a vector, one entry per argument. The result is the
exact inverse of the destructuring the handler performs:

```
handler                        :quint/args
[who amount]                   [:who :amount]
[{:keys [from to]}]            [{:from :src :to :dst}]
[[x y]]                        [[:x :y]]
[{:keys [pos]}]                [{:pos [:x :y]}]
[[{:keys [a]} b]]              [[{:a :pa} :b]]
{{:keys [inner]} :outer}       [{:outer {:inner [:p [:q :r]]}}]
```

Nothing that worked under 0010 changes: a flat vector of pick names and a flat
map of keyword to keyword are both still exactly what they were. This is
additive.

### Two boundaries, on purpose

**Sets are not composed.** Nothing destructures a set positionally, and a Quint
set decodes to a Clojure set that a pick name already delivers whole. A set as
an entry is `:bad-args`, and the message says why rather than only that.

**Composed map keys are keywords.** Clojure can destructure string keys, so
this is a restriction rather than a necessity. Quint records decode with
keyword keys, so keywords are the shape that meets them, and every real case
seen so far is one. It is trivially relaxable if a real spec asks; it is
written down here so that relaxing it is a decision rather than a slip.

## What this does not change

Every leaf is still a pick name held to the trace, `_`-prefixed leaves are
still exempt for the reason getting-started §3 already gives, and the keyword
still differs by source — `:bad-arglist` for the parameter list, `:bad-args`
for the annotation. The recursion changed what a template may look like, not
what a pick name means.

## The rule, now

The vocabulary is still six keys. `:quint/args` is now a small recursive
grammar over three node types with no operators, no evaluation, no conditionals
and no way to refer to anything but a pick. That is further than
[CLAUDE.md](../../CLAUDE.md)'s "never grow the annotations into a DSL" was
meant to go, and saying otherwise would be pretending.

What is claimed in exchange is that the grammar is not arbitrary: it has
exactly the shape of Clojure destructuring and cannot outgrow it, because it
exists only to be its inverse. **The next proposal to argue against is any node
type that Clojure destructuring does not have** — a conditional, a default, a
computed value, a reference to anything but a pick. Those would make it a
language. This is a mirror.

## Consequences

- No new error keyword. `:bad-args` covers the new rejections.
- `validation/args!`, `registry/arg-value` and `registry/needed-picks` are each
  recursive, and each is shorter than the flat version it replaced.
- 0010's grammar paragraph is superseded. Its reasoning about *why* the map
  form exists, and its rejected alternatives, are untouched and still the
  record.
