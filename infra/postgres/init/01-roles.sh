#!/usr/bin/env bash
# First-start initialisation of the EduCore database (mounted into /docker-entrypoint-initdb.d of postgres-db;
# the official image runs or sources it once, when the data directory is empty).
#
# POSTGRES_USER stays the bootstrap superuser and schema owner: Flyway migrates as it
# (EDUCORE_DB_MIGRATION_USERNAME in the backend) and the backup sidecar dumps as it. The backend's connection
# pool uses the least-privilege runtime role EDUCORE_DB_APP_USERNAME created here (DML only; see
# /opt/educore/app-role.sql = infra/postgres/app-role.sql). Re-running is safe (the role is updated, grants are
# idempotent), which is how an existing database is upgraded (docs/ops/UPGRADE.md):
#
#   docker compose exec postgres-db bash /docker-entrypoint-initdb.d/01-roles.sh
#
# The body runs in a subshell so that its shell options never leak into the image entrypoint when it is sourced.

educore_create_runtime_role() (
  set -euo pipefail

  : "${POSTGRES_USER:?POSTGRES_USER is required}"
  : "${POSTGRES_DB:?POSTGRES_DB is required}"
  : "${EDUCORE_DB_APP_USERNAME:?EDUCORE_DB_APP_USERNAME is required (the backend runtime role)}"
  : "${EDUCORE_DB_APP_PASSWORD:?EDUCORE_DB_APP_PASSWORD is required}"

  if [[ "${EDUCORE_DB_APP_USERNAME,,}" == "${POSTGRES_USER,,}" ]]; then
    echo "01-roles: EDUCORE_DB_APP_USERNAME must differ from EDUCORE_DB_USERNAME (the owner)" >&2
    exit 1
  fi
  if [[ ! "$EDUCORE_DB_APP_USERNAME" =~ ^[a-z_][a-z0-9_]{0,62}$ ]]; then
    echo "01-roles: EDUCORE_DB_APP_USERNAME must be a lower-case identifier ([a-z_][a-z0-9_]*)" >&2
    exit 1
  fi

  # The password reaches psql through the environment (\getenv in app-role.sql), not the command line.
  psql -v ON_ERROR_STOP=1 -q --no-psqlrc \
    --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v app_user="$EDUCORE_DB_APP_USERNAME" -v owner="$POSTGRES_USER" -v db="$POSTGRES_DB" \
    -f /opt/educore/app-role.sql

  echo "01-roles: runtime role '$EDUCORE_DB_APP_USERNAME' ready (DML only) in database '$POSTGRES_DB'"
)

educore_create_runtime_role
