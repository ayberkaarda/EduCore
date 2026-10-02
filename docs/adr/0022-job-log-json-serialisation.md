# 0022. Serialise job-log entries with Jackson and keep exception text out of job logs

- Status: Superseded by [ADR 0026](0026-ingestion-pipeline.md)
- Date: 2026-10-02
- Original decision: D-NEW-17

## Context

`config/JobTracker` built the job-log JSON (`[{status, message}]`) with `String.format` and hand-escaped quotes,
which produced invalid JSON for backslashes and line breaks in CSV values. For a failed student row,
`StudentMultiThreadService` stored `e.getMessage()`, so SQL fragments and constraint names reached the ADMIN
interface.

## Decision

`JobTracker` serialised the entries with Jackson, and the job-log text of a failed student row became a fixed
sentence instead of the exception message. The JSON shape was unchanged, so the frontend parsed it as before.

## Consequences

Positive:

- Valid JSON for every input; no database internals in the ADMIN interface.

Negative:

- Superseded: `JobTracker` and `StudentMultiThreadService` were later deleted together with the free-text
  `job_log.detailed_logs` column. Row outcomes are now `job_log_entry` rows with a fixed `reason` code and a
  masked raw line (`V30__ingestion_pipeline.sql`), as described in [ADR 0026](0026-ingestion-pipeline.md).

## References

- [`src/main/resources/db/migration/V30__ingestion_pipeline.sql`](../../src/main/resources/db/migration/V30__ingestion_pipeline.sql)
- [`src/main/java/com/educore/ingestion/JobLogEntry.java`](../../src/main/java/com/educore/ingestion/JobLogEntry.java)
- [`src/main/java/com/educore/ingestion/PiiMasker.java`](../../src/main/java/com/educore/ingestion/PiiMasker.java)
