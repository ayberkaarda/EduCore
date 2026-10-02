-- Least-privilege runtime role of the EduCore backend (AC-15). psql script, idempotent.
--
-- Inputs (psql variables): app_user (runtime role), owner (schema owner = POSTGRES_USER, the role Flyway migrates
-- with), db (database name). The runtime role's password is read from the environment variable
-- EDUCORE_DB_APP_PASSWORD (\getenv), never from the command line.
--
--   psql -v ON_ERROR_STOP=1 -v app_user=educore_app -v owner=educore_user -v db=educore_db -f app-role.sql
--
-- Run by infra/postgres/init/01-roles.sh on the first start of an empty data directory, and by hand for an
-- existing database (docs/ops/UPGRADE.md, "Least-privilege database role"). The runtime role gets CONNECT, USAGE
-- on schema public, SELECT/INSERT/UPDATE/DELETE on its tables, USAGE/SELECT/UPDATE on its sequences, and the same
-- through default privileges on every table and sequence the owner creates later (Flyway migrations). It is not a
-- superuser and owns nothing: it cannot DROP or ALTER tables, CREATE anything in the schema or the database
-- (extensions included), or run COPY ... TO/FROM PROGRAM or FILE.

\set ON_ERROR_STOP on
\getenv app_password EDUCORE_DB_APP_PASSWORD

-- Refuse a runtime role equal to the owner (it would demote the owner below) or a missing password.
SELECT format('DO $guard$ BEGIN RAISE EXCEPTION %L; END $guard$',
              'EDUCORE_DB_APP_USERNAME must differ from the owner role (POSTGRES_USER)')
WHERE lower(:'app_user') = lower(:'owner') \gexec
SELECT format('DO $guard$ BEGIN RAISE EXCEPTION %L; END $guard$', 'EDUCORE_DB_APP_PASSWORD is not set')
WHERE coalesce(:'app_password', '') = '' \gexec

SELECT format('CREATE ROLE %I LOGIN', :'app_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user') \gexec
SELECT format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS '
              'INHERIT PASSWORD %L', :'app_user', :'app_password') \gexec

-- Database: only the owner and the runtime role may connect; nobody else may create schemas or temp tables.
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'db') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'db', :'app_user') \gexec

-- Schema: use, never create.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'app_user') \gexec

-- Existing objects.
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', :'app_user') \gexec
SELECT format('GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO %I', :'app_user') \gexec

-- Objects the owner creates later (every Flyway migration runs as the owner).
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
              'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', :'owner', :'app_user') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
              'GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO %I', :'owner', :'app_user') \gexec
