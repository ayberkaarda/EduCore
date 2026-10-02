#!/usr/bin/env bash
# Docker healthcheck of the backup sidecar: unhealthy when the last complete backup is older than
# BACKUP_MAX_AGE_HOURS [26] (daily schedule + 2 h slack). Before the first run the container's start
# time is the reference, so a fresh sidecar stays healthy until its first scheduled run is overdue.
set -euo pipefail

max_hours="${BACKUP_MAX_AGE_HOURS:-26}"
root="${BACKUP_ROOT:-/backups}"
now="$(date -u +%s)"

if [[ -r "$root/last-success" ]]; then
  last="$(head -n 1 "$root/last-success")"
  what="last successful backup"
elif [[ -r /tmp/educore-backup-started ]]; then
  last="$(head -n 1 /tmp/educore-backup-started)"
  what="no successful backup yet; container started"
else
  echo "backup-health: no state found"
  exit 1
fi

[[ "$last" =~ ^[0-9]+$ ]] || { echo "backup-health: unreadable timestamp"; exit 1; }
age=$((now - last))
if (( age > max_hours * 3600 )); then
  echo "backup-health: UNHEALTHY, $what $((age / 3600))h ago (limit ${max_hours}h)"
  exit 1
fi
echo "backup-health: OK, $what $((age / 3600))h $(((age % 3600) / 60))m ago"
