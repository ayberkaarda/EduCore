#!/usr/bin/env bash
# Production preflight: checks a deployment's .env and host before
#   docker compose -f docker-compose.yml -f infra/backup/docker-compose.backup.yml -f docker-compose.prod.yml up -d
#
# Usage:
#   scripts/preflight.sh [--env-file PATH] [--offline] [--min-free-gb N]
#
#   --env-file PATH   environment file to check (default: .env in the repository root)
#   --offline         skip the network checks (DNS resolution and the live certificate probe)
#   --min-free-gb N   minimum free disk space for Docker volumes and backups (default: 5)
#
# Checks: required variables present; no example or placeholder values; secret lengths (JWT secret >= 32
# decoded bytes, login pepper >= 32 characters, encryption key = 32 decoded bytes for AES-256, database password
# >= 16, bootstrap admin password >= 12); EDUCORE_SEO_BASE_URL is an https origin; the public host resolves
# in DNS; the TLS certificate is valid for more than 14 days; the edge ports are free or already served by
# this stack; `docker compose config -q` passes; enough free disk space.
# Secret values are never printed, only variable names and lengths.
# Exit codes: 0 every check passed (warnings allowed), 1 at least one check failed, 2 usage error.
set -uo pipefail
export MSYS_NO_PATHCONV=1

root="$(cd "$(dirname "$0")/.." && pwd)"
env_file="$root/.env"
offline=false
min_free_gb=5

usage() { sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) env_file="${2:?--env-file needs a value}"; shift 2 ;;
    --offline) offline=true; shift ;;
    --min-free-gb) min_free_gb="${2:?--min-free-gb needs a value}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done
[[ -f "$env_file" ]] || { echo "preflight: env file not found: $env_file" >&2; exit 2; }
[[ "$min_free_gb" =~ ^[0-9]+$ ]] || { echo "preflight: --min-free-gb must be a whole number" >&2; exit 2; }

failures=0
warnings=0
pass() { printf 'PASS  %s\n' "$*"; }
fail() { printf 'FAIL  %s\n' "$*"; failures=$((failures + 1)); }
warn() { printf 'WARN  %s\n' "$*"; warnings=$((warnings + 1)); }

# Read KEY=VALUE lines without executing the file (no `source`): comments and blank lines are skipped,
# one level of surrounding quotes is removed. Later lines win, as in Compose.
declare -A env=()
while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%$'\r'}"
  [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
  [[ "$line" =~ ^[[:space:]]*(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || continue
  key="${BASH_REMATCH[2]}"
  value="${BASH_REMATCH[3]}"
  if [[ "$value" =~ ^\"(.*)\"$ ]] || [[ "$value" =~ ^\'(.*)\'$ ]]; then
    value="${BASH_REMATCH[1]}"
  fi
  env[$key]="$value"
done < "$env_file"

get() { printf '%s' "${env[$1]:-}"; }

echo "preflight: checking $(basename "$env_file") (values are never printed)"

# 1. Required variables (docker-compose.yml + docker-compose.prod.yml + the backup override).
required=(EDUCORE_DB_NAME EDUCORE_DB_USERNAME EDUCORE_DB_PASSWORD EDUCORE_JWT_SECRET EDUCORE_LOGIN_PEPPER
  EDUCORE_ENCRYPTION_KEY EDUCORE_SEO_BASE_URL EDUCORE_BOOTSTRAP_ADMIN_USERNAME EDUCORE_BOOTSTRAP_ADMIN_PASSWORD)
missing=()
for k in "${required[@]}"; do
  [[ -n "$(get "$k")" ]] || missing+=("$k")
done
if [[ ${#missing[@]} -eq 0 ]]; then
  pass "all ${#required[@]} required variables are set"
else
  fail "missing or empty: ${missing[*]}"
fi

# 2. Placeholder and example values (the ones shipped in .env.example and common stand-ins).
placeholder_re='(change[-_]?me|placeholder|example\.(org|com|net)|^example$|your[-_]|replace[-_]?me|^x{3,}$|^dummy|^test$|^secret$|^password$)'
placeholders=()
for k in "${!env[@]}"; do
  case "$k" in
    EDUCORE_*|BACKUP_S3_*) ;;
    *) continue ;;
  esac
  v="$(get "$k")"
  [[ -n "$v" ]] || continue
  if printf '%s' "$v" | grep -Eiq "$placeholder_re"; then
    placeholders+=("$k")
  fi
done
if [[ ${#placeholders[@]} -eq 0 ]]; then
  pass "no placeholder or example values"
else
  IFS=$'\n' read -r -d '' -a sorted < <(printf '%s\n' "${placeholders[@]}" | sort && printf '\0')
  fail "placeholder/example values in: ${sorted[*]}"
fi

# 3. Secret strength.
b64_bytes() {
  local v="$1"
  [[ "$v" =~ ^[A-Za-z0-9+/]+={0,2}$ ]] || { echo -1; return; }
  printf '%s' "$v" | base64 -d 2> /dev/null | wc -c | tr -d ' '
}
check_min_b64() {
  local k="$1" min="$2" v n
  v="$(get "$k")"; [[ -n "$v" ]] || return
  n="$(b64_bytes "$v")"
  if [[ "$n" -lt 0 ]]; then fail "$k is not valid base64"
  elif [[ "$n" -ge "$min" ]]; then pass "$k decodes to $n bytes (>= $min)"
  else fail "$k decodes to $n bytes, needs at least $min"; fi
}
check_min_len() {
  local k="$1" min="$2" v
  v="$(get "$k")"; [[ -n "$v" ]] || return
  if [[ ${#v} -ge "$min" ]]; then pass "$k has ${#v} characters (>= $min)"
  else fail "$k has ${#v} characters, needs at least $min"; fi
}
check_min_b64 EDUCORE_JWT_SECRET 32
check_min_len EDUCORE_LOGIN_PEPPER 32
key="$(get EDUCORE_ENCRYPTION_KEY)"
if [[ -n "$key" ]]; then
  n="$(b64_bytes "$key")"
  if [[ "$n" -eq 32 ]]; then pass "EDUCORE_ENCRYPTION_KEY decodes to 32 bytes (AES-256)"
  else fail "EDUCORE_ENCRYPTION_KEY must be base64 of exactly 32 bytes (got $n)"; fi
fi
check_min_len EDUCORE_DB_PASSWORD 16
check_min_len EDUCORE_BOOTSTRAP_ADMIN_PASSWORD 12
prev="$(get EDUCORE_JWT_SECRET_PREVIOUS)"
if [[ -n "$prev" ]]; then
  check_min_b64 EDUCORE_JWT_SECRET_PREVIOUS 32
  [[ "$prev" != "$(get EDUCORE_JWT_SECRET)" ]] || fail "EDUCORE_JWT_SECRET_PREVIOUS equals EDUCORE_JWT_SECRET"
fi

# 4. Public origin.
base="$(get EDUCORE_SEO_BASE_URL)"
host=""
if [[ -n "$base" ]]; then
  if [[ "$base" =~ ^https://([A-Za-z0-9.-]+)(:[0-9]{1,5})?/?$ ]]; then
    host="${BASH_REMATCH[1]}"
    pass "EDUCORE_SEO_BASE_URL is an https origin (host $host)"
  else
    fail "EDUCORE_SEO_BASE_URL must be an https origin without path (https://host[:port])"
  fi
fi

# 5. DNS.
resolve() {
  local h="$1"
  if command -v getent > /dev/null 2>&1; then getent ahosts "$h" > /dev/null 2>&1 && return 0; fi
  if command -v host > /dev/null 2>&1; then host "$h" > /dev/null 2>&1 && return 0; fi
  if command -v nslookup > /dev/null 2>&1; then nslookup "$h" 2> /dev/null | grep -Eq '^(Address|Addresses):?[[:space:]]+[0-9a-fA-F:.]+' && \
    nslookup "$h" 2> /dev/null | awk '/^Name:/{found=1} END{exit !found}' && return 0; fi
  return 1
}
if [[ "$offline" == true ]]; then
  warn "DNS check skipped (--offline)"
elif [[ -n "$host" ]]; then
  if [[ "$host" == "localhost" ]]; then warn "public host is localhost (test setup)"
  elif resolve "$host"; then pass "$host resolves in DNS"
  else fail "$host does not resolve in DNS"; fi
fi

# 6. Certificate validity (> 14 days): the mounted files (option A) or the live endpoint.
days=14
check_cert_file() {
  local f="$1"
  if ! command -v openssl > /dev/null 2>&1; then warn "openssl not found; certificate check skipped"; return; fi
  if ! openssl x509 -in "$f" -noout > /dev/null 2>&1; then fail "$f is not a PEM certificate"; return; fi
  if openssl x509 -in "$f" -noout -checkend $((days * 86400)) > /dev/null 2>&1; then
    pass "certificate $f is valid for more than $days days ($(openssl x509 -in "$f" -noout -enddate | cut -d= -f2))"
  else
    fail "certificate $f expires within $days days ($(openssl x509 -in "$f" -noout -enddate | cut -d= -f2))"
  fi
}
tls_source="$(get EDUCORE_TLS_SOURCE)"
if [[ -n "$tls_source" && "$tls_source" == /* || "$tls_source" =~ ^[A-Za-z]:[/\\] ]]; then
  if [[ -f "$tls_source/fullchain.pem" && -f "$tls_source/privkey.pem" ]]; then
    check_cert_file "$tls_source/fullchain.pem"
  else
    fail "EDUCORE_TLS_SOURCE=$tls_source must contain fullchain.pem and privkey.pem"
  fi
elif [[ "$offline" == true ]]; then
  warn "certificate check skipped (--offline; certificates come from the certbot volume)"
elif [[ -n "$host" ]] && command -v openssl > /dev/null 2>&1; then
  port="$(get EDUCORE_HTTPS_PORT)"; port="${port:-443}"
  pem="$(openssl s_client -connect "$host:$port" -servername "$host" < /dev/null 2> /dev/null | openssl x509 2> /dev/null || true)"
  if [[ -z "$pem" ]]; then
    warn "no certificate served at $host:$port yet (first start with --profile certbot issues it)"
  elif printf '%s\n' "$pem" | openssl x509 -noout -checkend $((days * 86400)) > /dev/null 2>&1; then
    pass "certificate served at $host:$port is valid for more than $days days"
  else
    fail "certificate served at $host:$port expires within $days days"
  fi
fi

# 7. Edge ports.
port_in_use() {
  local p="$1"
  if command -v ss > /dev/null 2>&1; then ss -ltnH 2> /dev/null | awk '{print $4}' | grep -Eq "[:.]$p$"; return; fi
  netstat -an 2> /dev/null | grep -Ei 'listen' | awk '{print $2}' | grep -Eq "[:.]$p$"
}
edge_running=false
if command -v docker > /dev/null 2>&1 && [[ "$(docker inspect -f '{{.State.Running}}' educore-frontend 2> /dev/null)" == "true" ]]; then
  edge_running=true
fi
for var in EDUCORE_HTTP_PORT EDUCORE_HTTPS_PORT; do
  p="$(get "$var")"
  if [[ -z "$p" ]]; then
    if [[ "$var" == EDUCORE_HTTP_PORT ]]; then p=80; else p=443; fi
  fi
  if ! [[ "$p" =~ ^[0-9]{1,5}$ ]] || (( p < 1 || p > 65535 )); then fail "$var is not a valid port"; continue; fi
  if port_in_use "$p"; then
    if [[ "$edge_running" == true ]]; then pass "port $p in use by the running EduCore edge"
    else fail "port $p ($var) is already in use by another process"; fi
  else
    pass "port $p ($var) is free"
  fi
done

# 8. Compose configuration.
if command -v docker > /dev/null 2>&1; then
  if (cd "$root" && docker compose --env-file "$env_file" -f docker-compose.yml -f infra/backup/docker-compose.backup.yml \
      -f docker-compose.prod.yml config -q > /dev/null 2>&1); then
    pass "docker compose config -q (base + backup + prod)"
  else
    fail "docker compose config -q failed (run it without -q to see the reason)"
  fi
else
  fail "docker CLI not found"
fi

# 9. Disk space where Docker keeps volumes (database and backups), else the repository's file system.
dir="$(docker info --format '{{.DockerRootDir}}' 2> /dev/null || true)"
[[ -n "$dir" && -d "$dir" ]] || dir="$root"
free_kb="$(df -Pk "$dir" 2> /dev/null | awk 'NR==2 {print $4}')"
if [[ -z "$free_kb" ]]; then
  warn "could not determine free disk space for $dir"
else
  free_gb=$((free_kb / 1024 / 1024))
  if (( free_gb >= min_free_gb )); then pass "free disk space ${free_gb} GB at $dir (>= ${min_free_gb} GB)"
  else fail "free disk space ${free_gb} GB at $dir, needs at least ${min_free_gb} GB for the database and backups"; fi
fi

echo "preflight: $failures failed, $warnings warning(s)"
if (( failures > 0 )); then exit 1; fi
exit 0
