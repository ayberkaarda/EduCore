-- Optimistic locking for course rows (JPA @Version on Course.version).
-- An ADMIN edit based on a stale read (for example a rename racing an unpublish) now fails with
-- 409 request/concurrent-modification instead of silently writing back the old published flag or slug.
ALTER TABLE course ADD COLUMN version bigint DEFAULT 0 NOT NULL;
