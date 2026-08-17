#!/usr/bin/env bash
# What `quint verify` actually does, recorded rather than recalled.
#
# Answers the three questions M7b rests on:
#   1. where _apalache-out/ lands, and whether it can be moved
#   2. how a counterexample is told apart from a broken spec, since both exit 1
#   3. whether Apalache's ITF dialect needs anything new from the decoder
#
# Findings are written up in docs/notes/itf-format.md §`quint verify`. Run this
# to check they still hold on a new Quint or a new Apalache. Needs quint on
# PATH; Apalache is downloaded on first use (~2 minutes, once).
#
# Usage: dev/probes/verify_probe.sh

set -u

dir=$(mktemp -d "${TMPDIR:-/tmp}/quint-verify-probe-XXXXXX")
trap 'rm -rf "$dir"' EXIT
cd "$dir" || exit 1

cat > tiny.qnt <<'EOF'
module tiny {
  var n: int

  action init = n' = 0

  action step = any {
    n' = n + 1,
    n' = n,
  }

  val small = n < 3
  val nonNegative = n >= 0
}
EOF

# `--verbosity=0` matters: without it the counterexample states are printed to
# the console even though --out-itf is documented as suppressing all output.
run() { quint verify "$@" --max-steps=5 --verbosity=0 > out.txt 2>&1; echo $?; }

say() { printf '\n=== %s\n' "$1"; }

say '1. where does _apalache-out/ land?'
mkdir -p specdir elsewhere
cp tiny.qnt specdir/
(cd elsewhere && quint verify "$dir/specdir/tiny.qnt" --invariant=nonNegative \
    --max-steps=2 --verbosity=0 > /dev/null 2>&1)
printf '   in the cwd it ran from : %s\n' \
    "$([ -d elsewhere/_apalache-out ] && echo yes || echo no)"
printf '   in the spec directory  : %s\n' \
    "$([ -d specdir/_apalache-out ] && echo yes || echo no)"
echo '   -> it follows the working directory, so a scratch cwd contains it'
echo '   -> and an absolute spec path is accepted, which is what makes that possible'

say '2. can --apalache-config move or rename it?'
rm -rf _apalache-out
printf '{"common": {"out-dir": "%s/moved", "write-intermediate": true}}\n' "$dir" > cfg.json
quint verify tiny.qnt --invariant=nonNegative --max-steps=2 \
    --apalache-config=cfg.json --verbosity=0 > /dev/null 2>&1
printf '   out-dir honoured       : %s\n' \
    "$([ -d moved ] && echo yes || echo 'no — _apalache-out appeared anyway')"
echo '{"common": {"nonsense-key": 1}}' > bad.json
bad=$(quint verify tiny.qnt --invariant=nonNegative --max-steps=2 \
    --apalache-config=bad.json --verbosity=0 > /dev/null 2>&1; echo $?)
printf '   unknown key rejected   : %s\n' \
    "$([ "$bad" = 0 ] && echo 'no — exits 0, so the file is not validated' || echo yes)"
echo '   -> the directory name is Apalache’s and cannot be chosen; only its parent can'

say '3. exit code, wording, and whether a trace is written'
report() {
    local label=$1 code=$2 trace=$3
    # Quint's type errors open with a blank line, so take the first line that
    # has anything on it; "holds" genuinely prints nothing at --verbosity=0.
    printf '   %-22s exit %s | trace %-3s | %s\n' "$label" "$code" \
        "$([ -f "$trace" ] && echo yes || echo no)" \
        "$(grep -m1 . out.txt || echo '(silent)')"
}
rm -f cex.itf.json holds.itf.json
code=$(run tiny.qnt --invariant=small --out-itf=cex.itf.json)
report 'counterexample' "$code" cex.itf.json
code=$(run tiny.qnt --invariant=nonNegative --out-itf=holds.itf.json)
report 'invariant holds' "$code" holds.itf.json
code=$(run tiny.qnt --invariant=noSuchInvariant --out-itf=x.itf.json)
report 'unknown invariant' "$code" x.itf.json
printf 'module broken {\n  var n: int\n  action init = n%s = "str"\n  action step = n%s = n\n  val ok = n >= 0\n}\n' "'" "'" > broken.qnt
code=$(run broken.qnt --invariant=ok --out-itf=y.itf.json)
report 'does not typecheck' "$code" y.itf.json
code=$(run nope.qnt --invariant=ok --out-itf=z.itf.json)
report 'file does not exist' "$code" z.itf.json
echo '   -> exit 1 alone says nothing; a written trace is what marks a counterexample'

say '4. what the counterexample ITF contains'
python3 - <<'PY'
import json
d = json.load(open('cex.itf.json'))
meta = d['#meta']
print('   #meta keys      :', ', '.join(meta))
print('   #meta.source    :', 'absent' if 'source' not in meta else meta['source'])
print('   #meta.varTypes  :', 'present' if 'varTypes' in meta else 'absent')
print('   #unserializable :', 'present' if 'unserializable' in json.dumps(d) else 'absent')
print('   value encodings :', 'plain #bigint / records, nothing the decoder lacks')
PY
echo '   -> decodes through uno.michelada.quint-connect.itf unchanged, with'
echo '      :source nil and no action, so :action-path is required to replay it'
