# 0027. Signed outbound webhooks with a bounded queue, fenced claims and an SSRF guard

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-31

## Context

External systems need to learn about completed imports, course changes and account deletions. Calling
arbitrary ADMIN-supplied URLs from the server creates SSRF risk, and a slow or hostile endpoint must not hold
the dispatcher. Producing services (courses, accounts) were already hardened and should not be edited to emit
events.

## Decision

- Events: `import.completed`, `import.failed`, `course.updated`, `account.deleted` (plus `webhook.test`, not
  subscribable). `course.updated` and `account.deleted` (mode `SOFT`) are produced by `WebhookProducerAspect`
  around `CourseService` and `AccountAdminService`, after the transaction committed. Payload `data` holds ids,
  enum values and counts only.
- Subscriptions (`webhook_subscription`): `https` URLs only, at most 20; the signing secret (`whsec_` + 64 hex)
  is returned once and stored AES-256-GCM encrypted under `EDUCORE_ENCRYPTION_KEY` (`SecretCipher`).
- Signing: `X-EduCore-Signature: v1=<hex HMAC-SHA256(secret, timestamp + "." + rawBody)>` with
  `X-EduCore-Timestamp`, `X-EduCore-Event` and `X-EduCore-Delivery`.
- Delivery (`WebhookDispatcher`): one delivery claimed at a time with `FOR UPDATE SKIP LOCKED`, a fresh
  `claim_token` (fencing) and a short lease; a 2xx answer is DELIVERED; anything else is retried with
  exponential backoff (30 s initial, 1 h cap, up to 20 % jitter) for at most 5 retries, then FAILED. Redirects are
  not followed and the response body is never read.
- Limits: 1 000 pending deliveries per subscription (further events dropped and counted in `dropped_events`),
  5 test events per ADMIN and minute, finished deliveries deleted after 14 days (`WebhookRetention`).
- SSRF guard: `WebhookUrls` validates URLs at create time; `WebhookAddressPolicy` blocks private, loopback,
  link-local, metadata and reserved ranges; the host is checked before sending and again by `GuardedDnsResolver`
  at connect time.

## Consequences

Positive:

- Receivers can authenticate requests and reject replays; the queue and dispatcher time are bounded.
- Producing services stay unchanged.

Negative:

- An event can be lost if the process stops between the producing commit and the enqueue (BACKLOG B-030; a
  transactional outbox is the proposed fix).
- Delivery is at-least-once; receivers must de-duplicate by `X-EduCore-Delivery`.

## References

- [`src/main/java/com/educore/webhook/WebhookDispatcher.java`](../../src/main/java/com/educore/webhook/WebhookDispatcher.java)
- [`src/main/java/com/educore/webhook/WebhookSigner.java`](../../src/main/java/com/educore/webhook/WebhookSigner.java)
- [`src/main/java/com/educore/webhook/WebhookAddressPolicy.java`](../../src/main/java/com/educore/webhook/WebhookAddressPolicy.java)
- [`src/main/java/com/educore/webhook/GuardedDnsResolver.java`](../../src/main/java/com/educore/webhook/GuardedDnsResolver.java)
- [`src/main/java/com/educore/webhook/WebhookProducerAspect.java`](../../src/main/java/com/educore/webhook/WebhookProducerAspect.java)
- [`src/main/java/com/educore/webhook/SecretCipher.java`](../../src/main/java/com/educore/webhook/SecretCipher.java)
- [`src/main/resources/db/migration/V31__webhooks.sql`](../../src/main/resources/db/migration/V31__webhooks.sql)
- [`src/test/java/com/educore/webhook/WebhookSsrfGuardTest.java`](../../src/test/java/com/educore/webhook/WebhookSsrfGuardTest.java)
- [`src/test/java/com/educore/webhook/WebhookRetryIT.java`](../../src/test/java/com/educore/webhook/WebhookRetryIT.java)
- [`docs/integrations/WEBHOOKS.md`](../integrations/WEBHOOKS.md)
- [`docs/BACKLOG.md`](../BACKLOG.md)
