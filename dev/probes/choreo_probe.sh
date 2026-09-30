#!/usr/bin/env bash
# What a Choreo spec actually emits, recorded rather than recalled.
#
# Answers the questions M9 rests on:
#   1. what `quint run --mbt` says about a Choreo spec as written
#   2. where an instrumented spec's transition lands, under `run` and `test`
#   3. what the no-argument variant `Init` decodes from
#   4. whether instrumentation changes which transitions a trace contains
#   5. whether `quint verify` runs on a Choreo spec at all
#   6. whether vendored imports resolve from somewhere other than the spec's own
#      directory, which is where `verify` runs
#   7. what state 0 says when Choreo as written runs to completion, per backend
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
python3 - <<'PY'
import json
states = json.load(open('run.itf.json'))['states']
def s(st): return next(v for k, v in st.items() if k.endswith('::s'))
noop = sum(1 for a, b in zip(states, states[1:])
           if s(a)['system'] == s(b)['system'] and s(a)['messages'] == s(b)['messages'])
print('   steps changing neither system nor messages: %d of %d' % (noop, len(states) - 1))
PY
rm -f many_*.itf.json
quint run two_phase_commit_tracked.qnt --mbt --seed=1 --max-steps=20 --n-traces=50 \
    --max-samples=50 --out-itf='many_{seq}.itf.json' --verbosity=0
python3 - <<'PY'
import glob, json
tot = noop = 0
for f in glob.glob('many_*.itf.json'):
    states = json.load(open(f))['states']
    s = lambda st: next(v for k, v in st.items() if k.endswith('::s'))
    for a, b in zip(states, states[1:]):
        tot += 1
        noop += s(a)['system'] == s(b)['system'] and s(a)['messages'] == s(b)['messages']
print('   across 50 traces of 20 steps: %d of %d, %.0f%%' % (noop, tot, 100.0 * noop / tot))
PY
echo '   -> yes: every transition now has an effect, so choreo::step no longer'
echo '      filters out the ones that change nothing'

say '5. quint verify on a Choreo spec'
mkdir -p scratch
for spec in two_phase_commit.qnt two_phase_commit_tracked.qnt; do
    (cd scratch && quint verify "$dir/$spec" --invariant=consistency \
        --max-steps=3 --out-itf=v.itf.json --verbosity=0 > out.txt 2>&1)
    printf '   %-30s exit %s | trace %-3s | %s\n' "$spec" "$?" \
        "$([ -f scratch/v.itf.json ] && echo yes || echo no)" \
        "$(grep -m1 . scratch/out.txt || echo '(silent)')"
done
echo '   -> fails, instrumented or not, and writes no trace. The message'
echo '      is Apalache'"'"'s type watchdog, not Quint'"'"'s: it lives in apalache.jar'

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
echo '   -> rust labels most initial states "step", with the picks of an attempt'
echo '      that was never taken; typescript labels every one "init"'
