# 0039. Lease fencing for ingestion, webhook DNS inside the deadline, and edge logs without query strings

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-93

## Context

Three timing and logging gaps remained after the ingestion, webhook and perimeter work
([0026](0026-ingestion-pipeline.md), [0027](0027-signed-outbound-webhooks.md),
[0025](0025-perimeter-https-proxies-and-rate-limiting.md)):

- Startup recovery of one instance could delete another instance's staged upload whose transaction had not yet
  committed (R-24, AC-16).
- A run whose lease expired was closed as `INTERRUPTED`, but its chunk writer kept writing rows. The job log then
  said nothing was imported although accounts existed (R-11, AC-17).
- The webhook pre-check resolved DNS before the request deadline was scheduled, so a slow resolver could hold the
  dispatcher beyond 10 s (R-25, AC-13).
- nginx used the default `combined` access log, which records full request lines (AC-14).

## Decision

- Uploads get an `upload_staging` row (`V34`: owner, lease, `STAGING`/`COMMITTED`), committed before the file is
  staged. Recovery takes over only uploads whose lease expired, through a conditional delete. Unregistered files
  from before V34 are handled only once they are older than one lease. An empty publish is logged at WARN.
- Every chunk of an import takes a shared, transaction-scoped advisory lock and then checks that the run is open,
  owned by this instance and leased (`IngestionFence`). The owner and recovery close runs only with conditional
  updates under the exclusive lock, so a close waits for chunks in flight, and later chunks fail with
  `LeaseLostException`. The heartbeat no longer revives an expired lease.
- Both webhook DNS lookups (pre-check and connect-time) run on a bounded executor of 4 threads within the request
  deadline. An overrun ends the attempt as `timeout`.
- The nginx access logs (`frontend/nginx.conf`, `infra/nginx/nginx.prod.conf`) are JSON. They log `$request_uri`
  and the referer cut at `?`, and the User-Agent only; no cookies and no credentials.

## Consequences

Positive:

- A closed run cannot write rows, and a sibling instance cannot delete an upload in flight.
- The advisory lock queue is fair. A `FOR SHARE` row lock was tried first and dropped, because continuous chunk
  traffic starved the close.
- DNS lookups cannot be interrupted, so the dispatcher waits at most for the time left and frees the thread when
  the resolver returns.

Negative:

- The heartbeat still shares the scheduler, so a long import can be interrupted needlessly; fencing keeps that
  safe (BACKLOG B-092).
- nginx's error log still writes the request line, query included, for failed upstream calls. No API secret
  travels in a query string (BACKLOG B-093).

## References

- [`src/main/java/com/educore/ingestion/IngestionFence.java`](../../src/main/java/com/educore/ingestion/IngestionFence.java)
- [`src/main/java/com/educore/ingestion/UploadStaging.java`](../../src/main/java/com/educore/ingestion/UploadStaging.java)
- [`src/main/resources/db/migration/V34__upload_staging.sql`](../../src/main/resources/db/migration/V34__upload_staging.sql)
- [`src/main/java/com/educore/webhook/HttpClientWebhookTransport.java`](../../src/main/java/com/educore/webhook/HttpClientWebhookTransport.java)
- [`frontend/nginx.conf`](../../frontend/nginx.conf), [`infra/nginx/nginx.prod.conf`](../../infra/nginx/nginx.prod.conf)
- [`src/test/java/com/educore/ingestion/IngestionFencingIT.java`](../../src/test/java/com/educore/ingestion/IngestionFencingIT.java)
- [`src/test/java/com/educore/ingestion/ImportUploadIT.java`](../../src/test/java/com/educore/ingestion/ImportUploadIT.java)
- [`src/test/java/com/educore/webhook/WebhookTransportTest.java`](../../src/test/java/com/educore/webhook/WebhookTransportTest.java)
