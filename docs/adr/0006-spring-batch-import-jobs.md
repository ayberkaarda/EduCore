# 0006. Replace the hand-written multi-threaded importer with Spring Batch jobs

- Status: Accepted
- Date: 2026-09-25
- Original decision: D-06

## Context

CSV imports were performed by `StudentMultiThreadService` (a hand-written thread pool) next to an older
Spring Batch configuration (`BatchConfig`, `CsvJobService`, `FileIntegrationConfig`, `JobTracker`). Two import
paths meant duplicated validation, no consistent skip or restart semantics and job logs built by string
formatting.

## Decision

Imports run only as Spring Batch jobs: `importStudentJob` and `importCourseJob` in
`com.educore.ingestion.batch.ImportJobConfig`, each with one multi-threaded, fault-tolerant chunk step
(synchronised `FlatFileItemReader`, blank-row filter, bean validation, de-duplication against the file and the
database, repository writer). Only parse, validation and data-integrity exceptions are skipped, up to
`educore.ingestion.skip-limit`. `StudentMultiThreadService`, `CsvJobService`, `BatchConfig`,
`FileIntegrationConfig` and `JobTracker` are deleted. The pipeline around the jobs is described in
[ADR 0026](0026-ingestion-pipeline.md).

## Consequences

Positive:

- One import implementation with standard chunk transactions, skip limits and execution metadata in the
  `BATCH_*` tables.
- Row failures are recorded as structured `job_log_entry` rows instead of free text.

Negative:

- Spring Batch adds its own schema (`V2__spring_batch_schema.sql`) and framework concepts that maintainers must
  know.
- With a multi-threaded step, the effective skip count can exceed the limit by up to `threads * chunkSize`
  before the job stops (documented on `EduCoreProperties.Ingestion`).

## References

- [`src/main/java/com/educore/ingestion/batch/ImportJobConfig.java`](../../src/main/java/com/educore/ingestion/batch/ImportJobConfig.java)
- [`src/main/resources/db/migration/V2__spring_batch_schema.sql`](../../src/main/resources/db/migration/V2__spring_batch_schema.sql)
- [`src/test/java/com/educore/ingestion/StudentImportJobIT.java`](../../src/test/java/com/educore/ingestion/StudentImportJobIT.java)
- [ADR 0026](0026-ingestion-pipeline.md)
