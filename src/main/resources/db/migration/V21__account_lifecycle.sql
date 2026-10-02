-- Account lifecycle (P7, security item S21): the soft-delete flag becomes an explicit status with a grace
-- period, and the audit trail can keep events of purged accounts under a pseudonym.
--
-- status:
--   ACTIVE            may authenticate and use every route its role allows
--   DEACTIVATED       soft-deleted by an ADMIN (the former deleted = 1); cannot authenticate
--   PENDING_DELETION  the owner requested erasure; may sign in until delete_after with the restore-only
--                     scope; AccountPurgeJob hard-deletes the row once delete_after has passed
--   DELETED           written by the purge inside its transaction immediately before the row is removed
-- deleted_at:   when the account left ACTIVE (unknown, hence NULL, for rows soft-deleted before V21)
-- delete_after: end of the grace period of a PENDING_DELETION account
-- The optimistic-lock column version (V20) is kept unchanged.

ALTER TABLE account ADD COLUMN status varchar(32);
ALTER TABLE account ADD COLUMN deleted_at timestamp(6) with time zone;
ALTER TABLE account ADD COLUMN delete_after timestamp(6) with time zone;

-- Data migration: deleted = 0 -> ACTIVE, any other value (the application only ever wrote 1) -> DEACTIVATED.
UPDATE account SET status = CASE WHEN deleted = 0 THEN 'ACTIVE' ELSE 'DEACTIVATED' END;

ALTER TABLE account ALTER COLUMN status SET DEFAULT 'ACTIVE';
ALTER TABLE account ALTER COLUMN status SET NOT NULL;
ALTER TABLE account ADD CONSTRAINT ck_account_status
    CHECK (status IN ('ACTIVE', 'DEACTIVATED', 'PENDING_DELETION', 'DELETED'));
ALTER TABLE account ADD CONSTRAINT ck_account_lifecycle_dates CHECK (
    (status = 'ACTIVE' AND deleted_at IS NULL AND delete_after IS NULL)
    OR (status = 'DEACTIVATED' AND delete_after IS NULL)
    OR (status = 'PENDING_DELETION' AND deleted_at IS NOT NULL AND delete_after IS NOT NULL)
    OR status = 'DELETED');

ALTER TABLE account DROP COLUMN deleted;

-- The purge job looks for due PENDING_DELETION rows only.
CREATE INDEX ix_account_purge_due ON account (delete_after) WHERE status = 'PENDING_DELETION';

-- Audit trail of purged accounts: the account id is replaced by 'purged:<16 hex>' (a keyed digest of the id),
-- so events stay countable and correlatable without pointing at a person. An event refers to an account
-- either by id or by pseudonym, never both.
ALTER TABLE security_event ADD COLUMN actor_pseudonym varchar(64);
ALTER TABLE security_event ADD COLUMN target_pseudonym varchar(64);
ALTER TABLE security_event ADD CONSTRAINT ck_security_event_actor_ref
    CHECK (actor_account_id IS NULL OR actor_pseudonym IS NULL);
ALTER TABLE security_event ADD CONSTRAINT ck_security_event_target_ref
    CHECK (target_account_id IS NULL OR target_pseudonym IS NULL);
ALTER TABLE security_event ADD CONSTRAINT ck_security_event_actor_pseudonym
    CHECK (actor_pseudonym IS NULL OR actor_pseudonym ~ '^purged:[0-9a-f]{16}$');
ALTER TABLE security_event ADD CONSTRAINT ck_security_event_target_pseudonym
    CHECK (target_pseudonym IS NULL OR target_pseudonym ~ '^purged:[0-9a-f]{16}$');

-- Lookups of the purge (by actor; ix_security_event_target exists since V10) and of the retention cleanup
-- (by time).
CREATE INDEX ix_security_event_actor ON security_event (actor_account_id);
CREATE INDEX ix_security_event_at ON security_event (at);
CREATE INDEX ix_login_attempt_at ON login_attempt (at);
