# 0026. File ingestion pipeline with private snapshots, leases and an idempotency ledger

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-30 (implements D-06)

## Context

CSV files arrive in a watched folder or through an ADMIN upload. The first pipeline had known defects: a
claimed file could still be changed by its writer or through a second hard link, accept-once marks could
be lost, crash recovery could close imports still running on another instance, and Spring Batch
metadata could contain CSV values (personal data).

## Decision

The legacy classes (`StudentMultiThreadService`, `CsvJobService`, `BatchConfig`, `FileIntegrationConfig`,
`JobTracker`) are deleted and replaced by `com.educore.ingestion`:

- Folders under `educore.ingestion.base-dir`: `inbox/` (watched), `staging/` (uploads awaiting commit),
  `processing/` (private snapshots), `done/` and `failed/` (with `.report.json` sidecars for PARTIAL and FAILED).
  All folders must be on one file store (startup check); transitions are atomic moves.
- Inbox files are never processed in place: links and multiply linked files are rejected, and the bytes are
  copied with a size cap into `processing/<uuid>_<name>`. Only the snapshot is validated, hashed and imported.
- The inbox adapter (`IngestionFlowConfig`) accepts `*.csv` files that are unchanged for `stable-after` and
  remembers accepted files in `INT_METADATA_STORE` (Spring Integration `JdbcMetadataStore`).
- The CSV header selects the job: exactly `FirstName,LastName,StudentNumber` (students) or
  `name,term,instructor` (courses). Imported students get their student number as username.
- `CsvPreLaunchValidator` checks size, strict UTF-8, text-only content, LF or CR LF line breaks (bare CR
  rejected), line length, header and row count, and computes the SHA-256 in the same pass.
- `IngestionLedger`: `imported_file` keyed by SHA-256 (same content is `DUPLICATE` unless the earlier import
  FAILED), `job_log` per run, `job_log_entry` per skipped row with a fixed reason and a masked raw line.
- Runs carry an owner and a lease (`job_log.owner`, `lease_until`) renewed by `IngestionInstance`'s heartbeat
  (30 s, lease 2 min). `IngestionRecovery` (startup and every 60 s) closes only runs whose lease expired, as
  FAILED / `INTERRUPTED`.
- Uploads are written to `staging/` with an `IMPORT_UPLOADED` audit event in one transaction and moved into
  `inbox/` after commit; startup publishes staged uploads whose audit event committed and deletes the rest.
- Batch sees only code-only exceptions, so exit messages and logs carry no CSV values or SQL.
- Results publish the `import.completed` or `import.failed` webhook.

## Consequences

Positive:

- What is validated is exactly what is imported; duplicate files are rejected by content.
- Safe with several application instances sharing one folder and database.

Negative:

- Requires a shared file store for all ingestion folders and more moving parts (leases, recovery, sidecars).
- Schema changes `V30`–`V32`; reverting means restoring the deleted classes and dropping those migrations.

## References

- [`src/main/java/com/educore/ingestion/IngestionDirectories.java`](../../src/main/java/com/educore/ingestion/IngestionDirectories.java)
- [`src/main/java/com/educore/ingestion/IngestionFlowConfig.java`](../../src/main/java/com/educore/ingestion/IngestionFlowConfig.java)
- [`src/main/java/com/educore/ingestion/IngestionService.java`](../../src/main/java/com/educore/ingestion/IngestionService.java)
- [`src/main/java/com/educore/ingestion/IngestionLedger.java`](../../src/main/java/com/educore/ingestion/IngestionLedger.java)
- [`src/main/java/com/educore/ingestion/IngestionRecovery.java`](../../src/main/java/com/educore/ingestion/IngestionRecovery.java)
- [`src/main/java/com/educore/ingestion/IngestionInstance.java`](../../src/main/java/com/educore/ingestion/IngestionInstance.java)
- [`src/main/java/com/educore/ingestion/ImportUploadService.java`](../../src/main/java/com/educore/ingestion/ImportUploadService.java)
- [`src/main/java/com/educore/ingestion/CsvPreLaunchValidator.java`](../../src/main/java/com/educore/ingestion/CsvPreLaunchValidator.java)
- [`src/main/resources/db/migration/V30__ingestion_pipeline.sql`](../../src/main/resources/db/migration/V30__ingestion_pipeline.sql)
- [`src/main/resources/db/migration/V32__ingestion_leases_webhook_fencing.sql`](../../src/main/resources/db/migration/V32__ingestion_leases_webhook_fencing.sql)
- [`src/test/java/com/educore/ingestion/IngestionDirectoryProtocolIT.java`](../../src/test/java/com/educore/ingestion/IngestionDirectoryProtocolIT.java)
- [`src/test/java/com/educore/ingestion/ImportUploadIT.java`](../../src/test/java/com/educore/ingestion/ImportUploadIT.java)
- [ADR 0006](0006-spring-batch-import-jobs.md)
