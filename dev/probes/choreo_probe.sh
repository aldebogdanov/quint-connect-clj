#!/usr/bin/env bash
# What a Choreo spec actually emits, recorded rather than recalled.
#
# Answers the questions M9 rests on:
#   1. what `quint run --mbt` says about a Choreo spec as written
#   2. where an instrumented spec's transition lands, under `run` and `test`
#   3. what the no-argument variant `Init` decodes from
#   4. whether instrumentation changes which transitions a trace contains
#   5. whether `quint verify` runs on a Choreo spec at all, why not, and
#      what TLC does instead
#   6. whether vendored imports resolve from somewhere other than the spec's own
#      directory, which is where `verify` runs
#   7. what state 0 says when Choreo as written runs to completion, per backend,
#      which is the bug that made Quint 0.33.0 the floor
#
# Findings are written up in docs/roadmap.md §M9 and docs/notes/itf-format.md
# §Choreo. Run this to check they still hold on a new Quint or a new Choreo.
#
# Needs bash, `quint` on PATH, and python3 to read the traces back. Section 5
# runs Apalache, which quint downloads on first use (~2 minutes, once). Nothing
# here touches the repository: the vendored specs are copied into a temporary
# directory, which is deleted on exit.
#
# Usage: dev/probes/choreo_probe.sh

set -u

here=$(cd "$(dirname "$0")/../fixtures/choreo" && pwd)
dir=$(mktemp -d "${TMPDIR:-/tmp}/quint-choreo-probe-XXXXXX")
trap 'rm -rf "$dir"' EXIT
cp -R "$here"/choreo.qnt "$here"/spells "$here"/two_phase_commit.qnt \
      "$here"/two_phase_commit_tracked.qnt "$dir"/
cd "$dir" || exit 1

say() { printf '\n=== %s\n' "$1"; }

# Prints, per state: index, mbt::actionTaken, the recorded transition's tag and
# value when the spec records one, and each node's stage by initial.
show() {
    python3 - "$1" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
for st in d['states']:
    s = next(v for k, v in st.items() if k.endswith('::s'))
    ext = s['extensions']
    rec = ext.get('actionTaken') if isinstance(ext, dict) else None
    stages = ''.join(v['stage']['tag'][0] for _, v in s['system']['#map'])
    print('   %-2s %-6s %-40s %s' % (
        st['#meta']['index'], st.get('mbt::actionTaken', '-'),
        json.dumps(rec) if rec else '-', stages))
PY
}

say '1. Choreo as written, under quint run --mbt'
quint run two_phase_commit.qnt --mbt --seed=42 --max-steps=6 --n-traces=1 \
    --max-samples=1 --out-itf=plain.itf.json --verbosity=0
show plain.itf.json
python3 - <<'PY'
import json
st = json.load(open('plain.itf.json'))['states'][1]
print('   picks at step 1 :', ', '.join(sorted(st['mbt::nondetPicks'])))
PY
echo '   -> "step" every time; the picks are a node and an outcome, never a name'

say '2. instrumented, under quint run --mbt and under quint test'
quint run two_phase_commit_tracked.qnt --mbt --seed=42 --max-steps=8 \
    --n-traces=1 --max-samples=1 --out-itf=run.itf.json --verbosity=0
show run.itf.json
quint test two_phase_commit_tracked.qnt --match='^commitTest$' \
    --out-itf=test.itf.json --verbosity=0
show test.itf.json
echo '   -> the transition is in s.extensions.actionTaken in both; quint test has'
echo '      no mbt:: at all, and still says which transition every step took'

say '3. what Init carries'
python3 - <<'PY'
import json
st = json.load(open('test.itf.json'))['states'][0]
s = next(v for k, v in st.items() if k.endswith('::s'))
print('   actionTaken at state 0 :', json.dumps(s['extensions']['actionTaken']))
PY
echo '   -> the empty tuple, which decodes to [] and is not a record of picks'

say '4. does recording change which transitions happen?'
# Recording a transition is an effect, and choreo::step keeps a transition with
# any effect, so it would stop dropping the ones that change nothing. The spec
# filters them itself with changes_something. Measured with that filter, and
# with it taken out of a copy.
sed 's/ctx => main_listener(ctx).filter(t => changes_something(ctx, t))/main_listener/' \
    two_phase_commit_tracked.qnt > unfiltered.qnt
noops() {
    rm -f many_*.itf.json
    quint run "$1" --mbt --seed=1 --max-steps=20 --n-traces=50 --max-samples=50 \
        --out-itf='many_{seq}.itf.json' --verbosity=0
    python3 - "$1" <<'PY'
import glob, json, sys
tot = noop = longest = 0
for f in glob.glob('many_*.itf.json'):
    states = json.load(open(f))['states']
    s = lambda st: next(v for k, v in st.items() if k.endswith('::s'))
    longest = max(longest, len(states) - 1)
    for a, b in zip(states, states[1:]):
        tot += 1
        noop += s(a)['system'] == s(b)['system'] and s(a)['messages'] == s(b)['messages']
print('   %-30s %4d of %4d steps change nothing (%2.0f%%); longest trace %d steps'
      % (sys.argv[1], noop, tot, 100.0 * noop / tot, longest))
PY
}
noops two_phase_commit_tracked.qnt
noops unfiltered.qnt
echo '   -> without the filter most steps repeat an instruction already acted on;'
echo '      with it, none do, and a trace ends when the protocol does'

say '5. quint verify on a Choreo spec, with Apalache and with TLC'
mkdir -p scratch
verify() {
    rm -f scratch/v.itf.json
    (cd scratch && quint verify "$dir/$1" --main="$2" --invariant="$3" "${@:4}" \
        --out-itf="$dir/scratch/v.itf.json" --verbosity=0 > out.txt 2>&1)
    printf '   %-30s %-12s %-15s exit %s | trace %-3s | %s\n' "$1" "$3" "${4:-apalache}" "$?" \
        "$([ -f scratch/v.itf.json ] && echo yes || echo no)" \
        "$(grep -m1 . scratch/out.txt | cut -c1-90 || echo '(silent)')"
}
verify two_phase_commit.qnt         two_phase_commit         consistency
verify two_phase_commit_tracked.qnt two_phase_commit_tracked consistency
verify two_phase_commit_tracked.qnt two_phase_commit_tracked consistency --backend=tlc
verify two_phase_commit_tracked.qnt two_phase_commit_tracked wit_commit  --backend=tlc
echo '   -> Apalache rejects both, each with an internal type-checking error'
echo '      (the first is its type watchdog'"'"'s, in apalache.jar). TLC checks the'
echo '      same spec: a holding invariant exits 0, a violated one exits 1 -- and'
echo '      writes no trace, because Quint writes --out-itf only from Apalache.'

# The smallest spec that reproduces it: a state variable whose type has a type
# parameter, fixed only when the module is instantiated. Choreo's
# `var s: GlobalContext[p, s, m, e, ext]` is that, so every Choreo spec is.
cat > poly.qnt <<'QNT'
module lib {
  const procs: Set[p]
  var s: p -> int
  action init = s' = procs.mapBy(x => 0)
  action step = { nondet v = oneOf(procs)
                  s' = s.set(v, 1) }
}
module main {
  import lib(procs = Set("a", "b")) as lib from "./poly"
  action init = lib::init
  action step = lib::step
  val inv = true
}
QNT
sed -e 's/Set\[p\]/Set[str]/' -e 's/var s: p -> int/var s: str -> int/' \
    -e 's#"./poly"#"./mono"#' poly.qnt > mono.qnt
verify poly.qnt main inv
verify mono.qnt main inv
echo '   -> the same module with its type written out passes'

say '6. do the imports resolve from another directory?'
mkdir -p elsewhere
(cd elsewhere && quint typecheck "$dir/two_phase_commit_tracked.qnt" > /dev/null 2>&1)
printf '   typecheck from elsewhere : exit %s\n' "$?"
echo '   -> relative to the importing file, not the working directory'

say '7. what state 0 says, on Choreo as written, per backend'
for backend in rust typescript; do
    rm -f s0_*.itf.json
    quint run two_phase_commit.qnt --mbt --backend=$backend --seed=42 --max-steps=12 \
        --n-traces=20 --max-samples=20 --out-itf='s0_{seq}.itf.json' --verbosity=0
    python3 - "$backend" <<'PY'
import glob, json, sys
labels = [json.load(open(f))['states'][0]['mbt::actionTaken'] for f in glob.glob('s0_*.itf.json')]
print('   %-10s %s' % (sys.argv[1], ', '.join('%d x %s' % (labels.count(a), a) for a in sorted(set(labels)))))
PY
done
echo '   -> every one "init" from Quint 0.33.0 on. On 0.32.0, rust labelled most of'
echo '      them "step", with the picks of an attempt never taken (Quint #2012)'
