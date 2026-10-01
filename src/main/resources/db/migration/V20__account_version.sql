-- Optimistic locking for account rows (JPA @Version on Account.version).
-- A write based on a stale read of an account (for example an admin edit racing a role change or a soft
-- delete) now fails instead of silently restoring the old role or deleted flag.
ALTER TABLE account ADD COLUMN version bigint DEFAULT 0 NOT NULL;
