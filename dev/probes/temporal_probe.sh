#!/usr/bin/env bash
# What `quint verify --temporal` actually does, recorded rather than recalled.
#
# Answers the questions `:temporal` in the driver rests on:
#   1. what happens under Apalache, the default backend, which Quint asks
#      about interactively before it runs -- with stdin closed, and open
#   2. what TLC does with an action property that holds, and one that does not
#   3. whether TLC writes a trace for a violated temporal property
#   4. whether TLC honours --max-steps
#   5. what the output says when --invariant and --temporal are checked at once
#
# Findings are written up in docs/notes/itf-format.md §`quint verify
# --temporal`. Run this to check they still hold on a new Quint.
#
# Needs bash, `quint` on PATH and coreutils' `timeout`. Apalache and TLC are
# downloaded on first use. Nothing here touches the repository: it works in a
# temporary directory and deletes it on exit.
#
# Usage: dev/probes/temporal_probe.sh

set -u

dir=$(mktemp -d "${TMPDIR:-/tmp}/quint-temporal-probe-XXXXXX")
trap 'rm -rf "$dir"' EXIT
cd "$dir" || exit 1

# The example from Quint 0.33.0's release announcement, plus an invariant.
cat > tiny.qnt <<'EOF'
module tiny {
  var x: int

  action init = x' = 1
  action step = all { x < 3, x' = x + 1 }

  temporal increasing = always((next(x) > x).orKeep(x))
  temporal jumps = always((next(x) > x + 1).orKeep(x))
  val small = x < 3
}
EOF

# A state space with no end: nothing stops n from growing.
cat > grow.qnt <<'EOF'
module grow {
  var n: int

  action init = n' = 0
  action step = n' = n + 1

  temporal up = always((next(n) > n).orKeep(n))
}
EOF

say() { printf '\n=== %s\n' "$1"; }

report() {
    local label=$1 code=$2
    printf '   %-40s exit %-3s | trace %-3s | %s\n' "$label" "$code" \
        "$([ -f out.itf.json ] && echo yes || echo no)" \
        "$(grep -v '^\s*$' out.txt | grep -m1 -v WARNING | cut -c1-80 || echo '(silent)')"
    rm -f out.itf.json
}

say '1. --temporal under Apalache, which asks before it runs'
quint verify tiny.qnt --temporal=jumps --out-itf=out.itf.json --verbosity=0 \
    < /dev/null > out.txt 2>&1
report 'stdin closed, property violated' "$?"
timeout 60 bash -c 'sleep 120 | quint verify tiny.qnt --temporal=jumps \
    --out-itf=out.itf.json --verbosity=0 > out.txt 2>&1'
report 'stdin open, never answered (timeout 60)' "$?"
echo '   -> closed: Quint exits as if the property held. Open: it waits for an answer'
echo '      that never comes. Neither is a verdict.'

say '2. and 3. TLC: an action property that holds, and one that does not'
quint verify tiny.qnt --backend=tlc --temporal=increasing --out-itf=out.itf.json \
    --verbosity=0 > out.txt 2>&1
report 'increasing (holds)' "$?"
quint verify tiny.qnt --backend=tlc --temporal=jumps --out-itf=out.itf.json \
    --verbosity=0 > out.txt 2>&1
report 'jumps (violated)' "$?"
echo '   -> exit 0 or 1, and no trace either way: Quint writes --out-itf only from'
echo '      Apalache'

say '4. TLC and --max-steps, on a state space with no end (timeout 60)'
timeout 60 quint verify grow.qnt --backend=tlc --temporal=up --max-steps=3 \
    --out-itf=out.itf.json --verbosity=0 > out.txt 2>&1
report 'grow, --max-steps=3' "$?"
echo '   -> 124 is timeout: TLC explores the whole state space, bounded or not'

say '5. --invariant and --temporal together, under TLC'
quint verify tiny.qnt --backend=tlc --invariant=small --temporal=increasing \
    --out-itf=out.itf.json --verbosity=0 > out.txt 2>&1
report 'small (violated) + increasing (holds)' "$?"
quint verify tiny.qnt --backend=tlc --invariant=small --temporal=jumps \
    --out-itf=out.itf.json --verbosity=0 > out.txt 2>&1
report 'small (violated) + jumps (violated)' "$?"
echo '   -> one verdict for both; the exit code does not say which was violated'
