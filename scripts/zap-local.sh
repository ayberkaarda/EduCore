#!/usr/bin/env bash
# OWASP ZAP baseline scan (passive rules plus a short spider) against a running EduCore edge.
#
# Usage:
#   scripts/zap-local.sh [TARGET_URL]
#
#   TARGET_URL    site root to scan (default: https://localhost). Self-signed certificates are accepted.
#
# Environment:
#   ZAP_NETWORK   Docker network for the ZAP container (default: host). To scan a local compose stack on
#                 Docker Desktop, join its network and target the edge by name, e.g.
#                 ZAP_NETWORK=educore_educore-network scripts/zap-local.sh https://educore-frontend:8443
#   ZAP_IMAGE     ZAP image (default: pinned ghcr.io/zaproxy/zaproxy:stable digest)
#   ZAP_MINUTES   spider duration in minutes (default: 2)
#
# Rules and their actions: zap/baseline.conf (every IGNORE carries a written justification). Reports
# (HTML, JSON, Markdown) are written to zap/reports/. Exit codes: 0 no High alert and no FAIL rule triggered,
# 1 at least one High alert or FAIL rule, 2 usage or tooling error.
set -euo pipefail
export MSYS_NO_PATHCONV=1

target="${1:-https://localhost}"
network="${ZAP_NETWORK:-host}"
image="${ZAP_IMAGE:-ghcr.io/zaproxy/zaproxy:stable@sha256:781a2bdaea47324e7bab583e2263f21d257b0aee61ed51521a5be45f5f5081ef}"
minutes="${ZAP_MINUTES:-2}"

die() { echo "zap-local: $*" >&2; exit 2; }
[[ "$target" =~ ^https?://[^[:space:]]+$ ]] || die "TARGET_URL must be an http(s) URL"
[[ "$minutes" =~ ^[1-9][0-9]*$ ]] || die "ZAP_MINUTES must be a positive integer"
command -v docker > /dev/null 2>&1 || die "docker CLI not found"

root="$(cd "$(dirname "$0")/.." && pwd)"
conf="$root/zap/baseline.conf"
[[ -f "$conf" ]] || die "missing $conf"
work="$root/zap/reports"
mkdir -p "$work"
cp "$conf" "$work/baseline.conf"
# The scanner runs as uid 1000 inside the image and writes its reports into the mounted directory.
chmod 0777 "$work" 2> /dev/null || true
rm -f "$work/zap-report.json" "$work/zap-report.html" "$work/zap-report.md"

host_work="$(cd "$work" && { pwd -W 2> /dev/null || pwd; })"

echo "zap-local: scanning $target (network: $network, spider: ${minutes} min)"
set +e
docker run --rm --network "$network" -v "$host_work:/zap/wrk:rw" "$image" \
  zap-baseline.py -t "$target" -c baseline.conf -m "$minutes" -I -j \
  -r zap-report.html -J zap-report.json -w zap-report.md
zap_rc=$?
set -e

report="$work/zap-report.json"
[[ -s "$report" ]] || die "ZAP produced no JSON report (exit $zap_rc)"

high="$(grep -c '"riskcode": "3"' "$report" || true)"
medium="$(grep -c '"riskcode": "2"' "$report" || true)"
low="$(grep -c '"riskcode": "1"' "$report" || true)"
echo "zap-local: alert types by risk: high=$high medium=$medium low=$low (reports in zap/reports/)"

# zap-baseline.py exits 1 when a rule configured as FAIL in baseline.conf triggered (-I ignores WARN).
if [[ "$zap_rc" -eq 1 ]]; then
  echo "zap-local: RESULT FAIL (a FAIL rule in zap/baseline.conf triggered)" >&2
  exit 1
fi
if [[ "$zap_rc" -gt 2 ]]; then
  die "ZAP ended with exit code $zap_rc"
fi
if [[ "$high" -gt 0 ]]; then
  echo "zap-local: RESULT FAIL ($high High alert type(s))" >&2
  exit 1
fi
echo "zap-local: RESULT OK (0 High)"
