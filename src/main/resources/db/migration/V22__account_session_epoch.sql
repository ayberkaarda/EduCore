-- Session boundaries (R-20, AC-10): every access token carries the account's session epoch (claim "sep") and
-- JwtAuthenticationFilter rejects a token whose epoch differs from the stored one. The epoch is incremented by
-- the owner's deletion request, an ADMIN soft delete, an ADMIN restore and the owner's restore, so access tokens
-- issued before such a change stop working at once instead of after their 15-minute lifetime. Tokens issued
-- before this migration carry no claim and count as epoch 0, the value every existing account starts with.
ALTER TABLE account ADD COLUMN session_epoch integer DEFAULT 0 NOT NULL;
