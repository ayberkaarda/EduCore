#!/usr/bin/env bash
# Checks that every environment variable the deployment reads is documented in .env.example, and that
# .env.example documents nothing that is no longer read.
#
# Usage: scripts/check-env-docs.sh
#
# Sources of variables that must be documented:
#   - ${VAR} interpolations in docker-compose*.yml and infra/backup/docker-compose.backup.yml
#   - ${VAR} placeholders in src/main/resources/application*.yml (Spring reads them from the environment)
# Every key in .env.example must be read by one of those, by a compose `environment:` entry, or by a script
# under scripts/ or infra/ (Spring relaxed binding of EDUCORE_* keys counts through the compose environment).
# Every key in .env.example needs a comment line directly above it (or above its group).
# README.md env-table coverage is reported as information only.
# Exit codes: 0 consistent, 1 a variable is undocumented or a documented key is unused.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

example=".env.example"
[[ -f "$example" ]] || { echo "check-env-docs: $example not found" >&2; exit 1; }

compose_files=(docker-compose*.yml infra/backup/docker-compose.backup.yml)
spring_files=(src/main/resources/application*.yml)

documented="$(grep -E '^[A-Z][A-Z0-9_]*=' "$example" | cut -d= -f1 | sort -u)"

# ${VAR}, ${VAR:-x}, ${VAR:?x}; nested defaults are matched too. $${VAR} (escaped, container-side) is skipped.
interp() { grep -hoE '(^|[^$])\$\{[A-Z][A-Z0-9_]*' "$@" 2> /dev/null | sed -E 's/.*\$\{//' | sort -u; }
compose_vars="$(interp "${compose_files[@]}")"
spring_vars="$(interp "${spring_files[@]}")"
# Keys set in compose `environment:` sections (list form KEY=... and map form KEY: ...).
compose_env_keys="$(grep -hoE '^[[:space:]]+(- )?[A-Z][A-Z0-9_]*[=:]' "${compose_files[@]}" | sed -E 's/^[[:space:]]+(- )?//; s/[=:]$//' | sort -u)"
script_vars="$(grep -rhoE '\b(EDUCORE|BACKUP|CERTBOT|EDGE)_[A-Z0-9_]+' scripts infra 2> /dev/null | sort -u)"

failures=0
indent() { local l; while IFS= read -r l; do printf '        %s\n' "$l"; done <<< "$1"; }

must_document="$(printf '%s\n%s\n' "$compose_vars" "$spring_vars" | sort -u | grep -v '^$')"
missing="$(comm -23 <(printf '%s\n' "$must_document") <(printf '%s\n' "$documented"))"
if [[ -n "$missing" ]]; then
  echo "FAIL  read by compose or Spring but missing from $example:"
  indent "$missing"
  failures=$((failures + 1))
else
  echo "PASS  every variable read by compose ($(printf '%s\n' "$compose_vars" | grep -c .)) and Spring ($(printf '%s\n' "$spring_vars" | grep -c .)) is in $example"
fi

used="$(printf '%s\n%s\n%s\n' "$must_document" "$compose_env_keys" "$script_vars" | sort -u)"
unused="$(comm -23 <(printf '%s\n' "$documented") <(printf '%s\n' "$used"))"
if [[ -n "$unused" ]]; then
  echo "FAIL  documented in $example but read nowhere:"
  indent "$unused"
  failures=$((failures + 1))
else
  echo "PASS  every key in $example ($(printf '%s\n' "$documented" | grep -c .)) is read by compose, Spring or a script"
fi

# Each key needs a comment in the block above it (a run of comment lines may document a group of keys).
undocumented="$(awk '
  /^#/ { commented = 1; next }
  /^[[:space:]]*$/ { commented = 0; next }
  /^[A-Z][A-Z0-9_]*=/ { split($0, kv, "="); if (!commented) print kv[1]; next }
' "$example")"
if [[ -n "$undocumented" ]]; then
  echo "FAIL  keys without an explanatory comment above them:"
  indent "$undocumented"
  failures=$((failures + 1))
else
  echo "PASS  every key in $example has an explanatory comment"
fi

if [[ -f README.md ]]; then
  not_in_readme="$(while read -r k; do grep -q "\b$k\b" README.md || echo "$k"; done <<< "$documented")"
  if [[ -n "$not_in_readme" ]]; then
    echo "INFO  keys not mentioned in README.md (documented in .env.example and docs/ops):"
    indent "$not_in_readme"
  else
    echo "INFO  README.md mentions every key"
  fi
fi

if (( failures > 0 )); then exit 1; fi
echo "check-env-docs: OK"
