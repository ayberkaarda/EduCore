-- One row per refresh token family. The row is the family's lock: rotation, reuse detection, logout and
-- password change take a row lock on it (SELECT ... FOR UPDATE or UPDATE) before reading or revoking the
-- family's tokens, so a revocation can never miss a successor that a concurrent rotation is inserting.
-- revoked_at is the persistent "family is dead" state, checked inside every rotating transaction.
CREATE TABLE refresh_token_family (
    id uuid NOT NULL,
    account_id bigint NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    revoked_at timestamp(6) with time zone,
    CONSTRAINT pk_refresh_token_family PRIMARY KEY (id),
    CONSTRAINT fk_refresh_token_family_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE CASCADE
);

CREATE INDEX ix_refresh_token_family_account ON refresh_token_family (account_id);

-- Existing families: revoked when none of their tokens is still active.
INSERT INTO refresh_token_family (id, account_id, created_at, revoked_at)
SELECT family_id,
       min(account_id),
       min(issued_at),
       CASE WHEN bool_and(revoked_at IS NOT NULL) THEN max(revoked_at) END
FROM refresh_token
GROUP BY family_id;

ALTER TABLE refresh_token
    ADD CONSTRAINT fk_refresh_token_family FOREIGN KEY (family_id) REFERENCES refresh_token_family (id)
        ON DELETE CASCADE;
