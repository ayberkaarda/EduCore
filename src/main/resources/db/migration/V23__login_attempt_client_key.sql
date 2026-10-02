-- Login lockout per (username, client) pair (R-01, AC-01): the hard lock now applies to the pair of the peppered
-- username hash and the canonical client key (IPv4 address, or IPv6 /64 network), so failures from one network
-- lock only that network out of that account. Existing rows get their stored address as key: IPv4 rows are
-- exact; IPv6 rows (full address) simply never match a /64 key again, which only forgets their old streaks.
ALTER TABLE login_attempt ADD COLUMN client_key varchar(64);
UPDATE login_attempt SET client_key = ip;
ALTER TABLE login_attempt ALTER COLUMN client_key SET NOT NULL;

CREATE INDEX ix_login_attempt_username_client_at ON login_attempt (username_hash, client_key, at DESC);
