# 0036. Erase processed CSV files by default

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-90

## Context

The ingestion pipeline ([0026](0026-ingestion-pipeline.md)) moved every imported file to `done/` or `failed/`
and kept it indefinitely, and the backup sidecar archived `done/` every night. The purge of an account removed
database rows but could not reach these files. The full name and student number of every imported student
therefore outlived an erasure (threat model R-05, attack chain AC-08).

## Decision

- A SUCCEEDED or PARTIAL snapshot is deleted right after the import (`educore.ingestion.retain-processed-days`,
  default 0). A value above 0 keeps it in `done/` for that many days.
- FAILED and rejected files and their `.report.json` stay in `failed/` for at most
  `educore.ingestion.retain-failed-days` (7), so an operator can fix and resubmit them. The hourly
  `IngestionRetention` then deletes them and never follows links.
- After a purge commits, the student's lines (matched by student number as a field) are removed from files still
  kept. The purge also deletes the account's `webhook_delivery` history and clears `job_log_entry` masks equal to
  the account's own line.
- The backup sidecar archives `csv_uploads/done` only with `BACKUP_ARCHIVE_CSV=true`.

## Consequences

Positive:

- The database keeps what an operator needs: hash, metadata, counts and masked row entries. Masked rows keep one
  character per value and do not single out a person.
- No copy of an erased student remains on disk or in a CSV archive.

Negative:

- A successful import cannot be re-inspected from its original file unless retention is raised.
- `job_log.file_name` and `imported_file.original_name` keep the operator-chosen file name; `DATA_RETENTION.md`
  says not to put personal names into it.

## References

- [`src/main/java/com/educore/ingestion/IngestionRetention.java`](../../src/main/java/com/educore/ingestion/IngestionRetention.java)
- [`src/main/java/com/educore/ingestion/IngestionService.java`](../../src/main/java/com/educore/ingestion/IngestionService.java)
- [`src/main/java/com/educore/lifecycle/AccountPurger.java`](../../src/main/java/com/educore/lifecycle/AccountPurger.java)
- [`infra/backup/docker-compose.backup.yml`](../../infra/backup/docker-compose.backup.yml)
- [`src/test/java/com/educore/ingestion/IngestionRetentionIT.java`](../../src/test/java/com/educore/ingestion/IngestionRetentionIT.java)
- [`docs/ops/DATA_RETENTION.md`](../ops/DATA_RETENTION.md)
- Related: [0026](0026-ingestion-pipeline.md), [0030](0030-audit-pseudonymisation-and-retention.md)
