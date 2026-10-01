#!/usr/bin/env sh
# Scan the working tree (not git history) for secrets using the repository's
# .gitleaks.toml. Exit code is non-zero when findings exist or gitleaks is missing.
#
# Usage: scripts/scan-secrets.sh [extra gitleaks args]
#   e.g. scripts/scan-secrets.sh --report-path gitleaks-report.json --report-format json
set -eu

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if ! command -v gitleaks >/dev/null 2>&1; then
  echo "scan-secrets: gitleaks is not installed or not on PATH." >&2
  echo "scan-secrets: install it from https://github.com/gitleaks/gitleaks/releases and re-run." >&2
  exit 2
fi

cd "$REPO_ROOT"
exec gitleaks detect --no-git --source . --config "$REPO_ROOT/.gitleaks.toml" --redact --no-banner "$@"
