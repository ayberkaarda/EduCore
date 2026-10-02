-- Owner and lease of manual CSV uploads waiting in staging/ (AC-16).
--
-- The row is committed BEFORE the file is written to staging/ (state STAGING, owner = the instance id, lease
-- renewed by that instance's heartbeat). The upload transaction moves it to COMMITTED together with the
-- IMPORT_UPLOADED audit event; after the commit the owner moves the file into the inbox and deletes the row, and
-- a rollback deletes file and row. Recovery on any instance touches only rows whose lease expired: an expired
-- COMMITTED upload is published, an expired STAGING upload is discarded. Both transitions are conditional
-- updates, so an owner and a recovery can never both act on one upload.
--
-- IF NOT EXISTS: see V33 (pg_restore --clean of an older dump keeps tables that the dump does not contain).

CREATE TABLE IF NOT EXISTS upload_staging (
    token uuid NOT NULL,
    owner varchar(64) NOT NULL,
    state varchar(16) NOT NULL,
    lease_until timestamp(6) with time zone NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT pk_upload_staging PRIMARY KEY (token),
    CONSTRAINT ck_upload_staging_state CHECK (state IN ('STAGING', 'COMMITTED'))
);

CREATE INDEX IF NOT EXISTS ix_upload_staging_lease ON upload_staging (lease_until);
