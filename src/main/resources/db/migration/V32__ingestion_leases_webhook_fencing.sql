-- P6 review fixes.

-- Import ownership: the instance that runs an import renews lease_until while it works; startup and periodic
-- recovery close only runs whose lease expired, never a live run of another instance. snapshot_name is the
-- private copy of the file in processing/ that the run reads.
ALTER TABLE job_log ADD COLUMN owner varchar(64);
ALTER TABLE job_log ADD COLUMN lease_until timestamp(6) with time zone;
ALTER TABLE job_log ADD COLUMN snapshot_name varchar(255);
CREATE INDEX ix_job_log_open ON job_log (lease_until) WHERE status IS NULL;

-- Webhook delivery fencing: a dispatcher claims one delivery at a time with a fresh claim_token and a short
-- lease (next_attempt_at); result updates only apply while the token is still the row's token.
ALTER TABLE webhook_delivery ADD COLUMN claim_token uuid;

-- Events not queued because the subscription already had the maximum number of pending deliveries.
ALTER TABLE webhook_subscription ADD COLUMN dropped_events bigint DEFAULT 0 NOT NULL;

CREATE INDEX ix_webhook_delivery_retention ON webhook_delivery (status, created_at);
