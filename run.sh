#!/usr/bin/env bash
#
# The whole argument in one run: a policy, a check that executes it, an action
# gated on the check, and a trail that survives the policy changing underneath.
#
# Usage: ./run.sh
set -euo pipefail
cd "$(dirname "$0")"

export RECON_CLOCK="2026-07-20T09:00:00Z"
rm -f audit/audit.jsonl

say() { printf '\n\033[1m%s\033[0m\n' "$1"; }
run() { printf '\n\033[2m$ %s\033[0m\n' "$*"; "$@" || true; }

say "1. The policy is configuration, not code"
sed -n '15,25p' policy/tolerances.v1.yaml

say "2. The queue"
run ./recon list

say "3. The break as the agent may see it, and the counterparty's history"
run ./recon show-break B-1001
run ./recon history B-1001

say "4. A small, clean difference. Code checks it against the tolerance."
run ./recon check B-1001

say "5. The agent proposes closure, with every claim sourced. The analyst confirms."
run ./recon propose-closure B-1001 \
  --rationale "Difference of 120.00 USD is within the 250.00 USD tolerance per TOLERANCE-USD (verdict from check_break). The break note attributes it to day-count rounding on the fixed leg, and the two past breaks with CP-4471 (H-1904, H-1930) both closed cleanly within tolerance."
export RECON_CLOCK="2026-07-20T09:01:00Z"
run ./recon confirm B-1001 --analyst asel

say "6. Now a break the policy protects. The rationale is fluent and wrong."
export RECON_CLOCK="2026-07-20T09:02:00Z"
run ./recon propose-closure B-1003 \
  --rationale "The counterparty has a long history of clean settlement, the difference is small, and similar breaks self-resolved last week. Recommend closure."

say "7. Within tolerance, but an amendment is in flight. Still refused."
export RECON_CLOCK="2026-07-20T09:03:00Z"
run ./recon propose-closure B-1002 \
  --rationale "Difference of 180.00 is inside the 250.00 tolerance. Recommend closure."

say "8. So it escalates instead, with findings and no recommendation."
export RECON_CLOCK="2026-07-20T09:04:00Z"
run ./recon escalate B-1002 \
  --findings "Amendment booked shortly after the original trade is still flowing through the chain; the 180.00 difference matches the correction. Counterparty confirmed the current version. No open dispute. Suggest re-check after the next matching cycle."

say "9. Operations Risk tightens the swap tolerance. Nothing else changes."
export RECON_POLICY=policy/tolerances.v2.yaml
export RECON_CLOCK="2026-07-20T09:05:00Z"
run ./recon check B-1001

say "10. The same break is no longer closeable, and yesterday's closure still
   points at the version it was closed under."
run ./recon audit B-1001

say "11. The trail is hash-chained"
run ./recon verify-audit

printf '\n\033[1mTry it yourself:\033[0m open this directory in Claude Code and ask it to work the queue.\n\n'
