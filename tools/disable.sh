#!/usr/bin/env bash
# Disable every package in a debloat list (one pm call per shell command; chained pm calls can stop early).
# Packages are disabled for user 0, never uninstalled. Undo with tools/restore.sh <same list>.
# Usage: tools/disable.sh examples/tcl-google-tv-g07/debloat.txt
# Fire OS prints "Success" even for protected packages it refuses, so the result is read back from
# `pm list packages -d` afterwards (cleanyourtv's rule: never trust exit codes). Re-run after an OTA
# or Appstore update: they turn packages back on.
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: tools/disable.sh <debloat list>}"
PY="${PY:-tools/venv/bin/python}"
WANT=$(grep -vE '^\s*(#|$)' "$LIST" | awk '{print $1}')
echo "$WANT" | awk '{print "pm disable-user --user 0 " $1}' | "$PY" -I tools/tv.py --batch >/dev/null
OFF=$("$PY" -I tools/tv.py 'pm list packages -d' | sed 's/^package://' | tr -d '\r')
ALL=$("$PY" -I tools/tv.py 'pm list packages' | sed 's/^package://' | tr -d '\r')
left=0
for p in $WANT; do
  if grep -qx "$p" <<<"$OFF"; then continue; fi
  left=$((left + 1))
  if grep -qx "$p" <<<"$ALL"; then echo "still on (protected?): $p"; else echo "not on this TV: $p"; fi
done
echo "$(($(wc -w <<<"$WANT") - left)) of $(wc -w <<<"$WANT") disabled."
