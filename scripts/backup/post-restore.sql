-- Session reset and erasure replay request after a database restore (docs/ops/BACKUP_RESTORE.md, AC-09).
--
-- scripts/backup/post-restore.sh runs this inside the transaction that also re-inserts the erasure ledger entries
-- of the ledger volume (which the dump cannot rewind). It needs no secret: the HMAC matching of restored accounts
-- against the ledger happens in the backend, which completes the pending replay at startup before it serves a
-- request. Every statement is safe to repeat.

-- Every refresh token and family the dump brought back is dead: sessions revoked after the dump was taken (logout,
-- password change, deletion request, deactivation) must not work again, and nobody can tell which those are.
UPDATE refresh_token SET revoked_at = now() WHERE revoked_at IS NULL;
UPDATE refresh_token_family SET revoked_at = now() WHERE revoked_at IS NULL;

-- Access tokens issued before the restore carry the old session epoch (JWT claim "sep") and stop working at once.
UPDATE account SET session_epoch = session_epoch + 1;

-- Ask the backend to purge every restored account the erasure ledger lists, before it accepts traffic.
INSERT INTO restore_replay (source, requested_at) VALUES ('POST_RESTORE', now());
