#!/usr/bin/env bash
# Canonical DastYab verification gate: fmt + build + clippy + full core
# test suite in one shot. Exit non-zero if anything fails.
#
# Usage: scripts/verify.sh
set -u
cd "$(dirname "$0")/.."
PASS=0; FAIL=0

step() {
  local name="$1"; shift
  if "$@" >/tmp/dyb-verify.log 2>&1; then
    echo "PASS $name"; PASS=$((PASS+1))
  else
    echo "FAIL $name"; FAIL=$((FAIL+1)); tail -20 /tmp/dyb-verify.log
  fi
}

step "fmt"             cargo fmt --all -- --check
step "workspace-clippy" cargo clippy --workspace -- -D warnings
step "workspace-tests"  timeout 600 cargo test --workspace
if python3 -m pytest --version >/dev/null 2>&1; then
  step "python-tests"  python3 -m pytest tests/ -q
else
  echo "SKIP python-tests (pytest not installed)"
fi

echo "======== RESULT ========"
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
