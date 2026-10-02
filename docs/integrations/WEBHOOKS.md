# Outbound webhooks

EduCore sends signed HTTPS `POST` requests to subscribed endpoints when something happens in the platform.
Subscriptions are managed by ADMINs under `/api/v1/admin/webhooks` (see [ROUTES.md](../api/ROUTES.md)).

## Events

| Event | Sent when | `data` |
|---|---|---|
| `import.completed` | A CSV import finished as `SUCCEEDED` or `PARTIAL` | `{jobLogId, importedFileId, kind, status, reason, rows: {read, written, skipped}}` (opaque ids only; the file name, which may contain personal data, is visible to ADMINs in the job log only) |
| `import.failed` | A CSV import finished as `FAILED` (rejected file, failed job, nothing written) | same as above; `reason` names the cause, e.g. `INVALID_HEADER`, `DUPLICATE`, `SKIP_LIMIT_EXCEEDED` |
| `course.updated` | An ADMIN created, changed or deleted a course | `{courseId, action: CREATED \| UPDATED \| DELETED}` |
| `account.deleted` | An ADMIN deleted an account | `{accountId, mode: SOFT}` |
| `webhook.test` | An ADMIN pressed "send test event" (`POST /api/v1/admin/webhooks/{id}/test`); sent to that subscription only, even when it is inactive, and cannot be subscribed to | `{webhookId, message}` |

Payloads carry ids, enum values and counts only: no names, student numbers, usernames, e-mail addresses or
file names.

Guarantees: an event is queued after the change committed (a change that rolls back sends nothing), and a
queued delivery is sent at least once (retries reuse `X-EduCore-Delivery`; deduplicate on it). Known
limitation: the event is queued in its own transaction right after the commit, so a crash between the commit
and the enqueue loses that event (no transactional outbox yet, BACKLOG B-030). Reconcile from the admin API
(job logs, courses) if you must not miss an event.

## Request

```
POST <subscription url>
Content-Type: application/json; charset=UTF-8
User-Agent: EduCore-Webhooks/1
X-EduCore-Event: course.updated
X-EduCore-Delivery: 5b0f8a4e-0c1d-4f7e-9d55-0f1f5c2f9a10
X-EduCore-Timestamp: 1790000000
X-EduCore-Signature: v1=3f6c...e1

{"id":"5b0f8a4e-0c1d-4f7e-9d55-0f1f5c2f9a10","event":"course.updated","createdAt":"2026-10-01T09:00:00Z","data":{"action":"UPDATED","courseId":42}}
```

- `X-EduCore-Delivery` is the delivery id (also `id` in the body). Retries of one delivery reuse it: use it to
  ignore duplicates.
- `X-EduCore-Timestamp` is the send time of this attempt in Unix seconds.
- `X-EduCore-Signature` is `v1=` followed by the lowercase hex HMAC-SHA256 of `timestamp + "." + rawBody`,
  keyed with the UTF-8 bytes of the subscription secret (`whsec_` + 64 hex characters, shown once when the
  subscription is created).

## Verifying a request

1. Read the raw body bytes before any JSON parsing (re-serialised JSON does not match).
2. Reject the request when `X-EduCore-Timestamp` is more than 5 minutes away from your clock (replay window).
3. Compute `v1=` + hex(HMAC-SHA256(secret, timestamp + "." + rawBody)) and compare it with
   `X-EduCore-Signature` in constant time.
4. Answer `2xx` quickly (within 5 seconds) and process asynchronously.

### Java

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

public final class EduCoreWebhookVerifier {

    private static final long TOLERANCE_SECONDS = 300;

    public static boolean isValid(String secret, String timestampHeader, String signatureHeader, byte[] rawBody)
            throws Exception {
        if (timestampHeader == null || signatureHeader == null) {
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(Instant.now().getEpochSecond() - timestamp) > TOLERANCE_SECONDS) {
            return false;
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestampHeader + ".").getBytes(StandardCharsets.UTF_8));
        String expected = "v1=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signatureHeader.getBytes(StandardCharsets.US_ASCII));
    }
}
```

### Node.js

```js
const crypto = require('node:crypto');

const TOLERANCE_SECONDS = 300;

// rawBody must be the Buffer of the unparsed request body, e.g. express.raw({ type: 'application/json' }).
function isValidEduCoreWebhook(secret, timestampHeader, signatureHeader, rawBody) {
  if (!timestampHeader || !signatureHeader || !/^\d+$/.test(timestampHeader)) return false;
  const age = Math.abs(Math.floor(Date.now() / 1000) - Number(timestampHeader));
  if (age > TOLERANCE_SECONDS) return false;
  const expected = 'v1=' + crypto.createHmac('sha256', Buffer.from(secret, 'utf8'))
    .update(timestampHeader + '.')
    .update(rawBody)
    .digest('hex');
  const a = Buffer.from(expected, 'ascii');
  const b = Buffer.from(signatureHeader, 'ascii');
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

module.exports = { isValidEduCoreWebhook };
```

## Delivery and retries

- The dispatcher runs every `educore.webhook.dispatch-interval` (5 s) and sends up to
  `educore.webhook.batch-size` (20) due deliveries per run, claiming each one immediately before sending it
  (`FOR UPDATE SKIP LOCKED`, a fresh claim token, a lease of `request-deadline` + 30 s). Result updates are
  fenced by the token: if a lease expired and another instance re-claimed the delivery, the late result of
  the first sender is ignored. An attempt cut short by a crash becomes due again when its lease ends.
- Each request must produce a status line and headers within `educore.webhook.request-deadline` (10 s)
  overall, in addition to the connect and read timeouts; the response body is never read (the connection is
  discarded once the status is known), so a slow or endless response cannot hold the dispatcher. The deadline
  also bounds both DNS lookups (the pre-check and the connect-time resolution), which run on a bounded pool of
  4 resolver threads; a lookup that does not finish in time ends the attempt as `timeout`.
- A `2xx` answer marks the delivery `DELIVERED`. Anything else is a failed attempt: other statuses
  (`3xx` included: redirects are never followed), connect or read timeouts (5 s each,
  `educore.webhook.connect-timeout` / `read-timeout`), TLS or DNS failures, and blocked targets.
- A failed delivery is retried at most `educore.webhook.max-retries` (5) times, i.e. 6 attempts in total.
  Retry `n` waits `initial-backoff * 2^(n-1)` (30 s, 1 min, 2 min, 4 min, 8 min), capped at `max-backoff`
  (1 h), plus up to 20 % random jitter. After the last retry the delivery is `FAILED`.
- Limits: at most `educore.webhook.max-subscriptions` (20) subscriptions (409 `webhook/limit-reached`); at
  most `max-pending-per-subscription` (1 000) PENDING deliveries per subscription — further events are not
  queued and are counted in the subscription's `droppedEvents`; "send test event" is limited to
  `test-events-per-minute` (5) per ADMIN (429 `webhook/too-many-test-events` with `Retry-After`) and refused
  while the queue is full (409 `webhook/queue-full`). DELIVERED and FAILED deliveries are deleted after
  `delivery-retention` (14 days); PENDING ones are kept.
- Deleting a subscription deletes its deliveries; deactivating it makes its pending deliveries `FAILED`
  (`subscription-inactive`).
- `GET /api/v1/admin/webhooks/{id}/deliveries` lists status, attempt count, last response code and a fixed
  error code (`http-500`, `timeout`, `redirect-not-followed`, `blocked-address`, `dns-failure`,
  `tls-failure`, `connection-failure`, `secret-unavailable`).

## Security

- Subscription URLs must be absolute `https` URLs without user info or fragment (database check and API
  validation). Plain HTTP is never used.
- SSRF guard: the host is resolved when each attempt is sent, and the HTTP client's own DNS resolver repeats
  the check for the address it connects to. Loopback, private (RFC 1918), carrier-grade NAT, link-local
  (including `169.254.169.254`), unique-local IPv6 (including `fd00:ec2::254`), multicast, reserved and
  documentation ranges, IPv4-mapped/NAT64 forms of those, and the names `localhost`, `*.localhost`,
  `metadata`, `metadata.google.internal` are rejected (`WebhookAddressPolicy`). IP literals in these ranges
  are already refused when the subscription is saved.
- No proxy from system properties, no cookies, no automatic retries inside the HTTP client.
- Secrets are generated by the server (32 random bytes), returned once, and stored encrypted with AES-256-GCM
  (random 96-bit IV, 128-bit tag) under `EDUCORE_ENCRYPTION_KEY` (base64 of 32 bytes, required in `prod`;
  generate with `openssl rand -base64 32`). Rotating that key makes stored secrets unreadable: re-create the
  subscriptions afterwards. A lost secret cannot be read back; delete and re-create the subscription.
- Every create, update, delete and test request writes an audit event (`WEBHOOK_CHANGED`,
  `WEBHOOK_TEST_REQUESTED`).
