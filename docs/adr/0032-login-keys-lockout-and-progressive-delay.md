# 0032. Login throttling keyed by client network, per-pair lockout and a progressive delay

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-80

## Context

Before this decision the login lock was keyed by the username only: five wrong passwords from anywhere locked an
account for 15 minutes, and nothing could unlock it. Anyone who knew a username could keep that account locked.
The bootstrap administrator's name was one such username, and so was every imported student's username, which is
the student number. The login throttle and the auto-deny counter used the full client address, so a client
rotating addresses inside one IPv6 /64 was never throttled, and the login limiter's cache evicted old buckets
under load (threat model R-01 and R-04, attack chains AC-01 and AC-06).

## Decision

- The login limiter, the lockout and the auto-deny counter share one client key, `ClientAddress.clientKey`: the
  exact IPv4 address, IPv4-mapped IPv6 normalised to IPv4, and native IPv6 reduced to its /64 network.
- The login limiter has its own `CaffeineRateLimitStore`. Like the general store, it admits new keys only below
  its size limit and sends the rest to an overflow bucket; it never evicts existing buckets.
- The hard lock applies to a (username hash, client key) pair (`V23` adds `login_attempt.client_key`): five
  failures within 15 minutes lock that pair for 15 minutes (423 `auth/account-locked`).
- A per-username progressive delay slows distributed guessing (`educore.security.login.account-throttle`). The
  first five failures are free; after that each attempt waits 1 s, doubling up to 30 s (429
  `auth/too-many-attempts` with `Retry-After`). A client key that signed in to the account successfully within
  the last 30 days is exempt.
- `POST /api/v1/admin/accounts/{id}/unlock-login` deletes the failed attempts of an account and writes
  `ACCOUNT_LOGIN_UNLOCKED`. Break-glass SQL for the case where no ADMIN can sign in is in
  `docs/ops/RUNBOOK_ADMIN_RECOVERY.md`.
- IPv6 /64 networks that reach the auto-deny threshold are denied the login endpoint in memory, because deny
  rules are IPv4 only.

## Consequences

Positive:

- Only the network that keeps guessing is locked; the owner signs in from their usual network during an attack.
- Distributed guessing is held to about two guesses a minute per username without a hard lock, because the delay
  is a rate limit and never lasts more than 30 s after the last failure.
- IPv6 address rotation inside one /64 no longer resets the throttle.

Negative:

- An attacker who keeps failing slows the owner by at most 30 s per attempt on a network that never signed in
  before (BACKLOG B-086).
- IPv6 login denials live in memory per instance and cannot be listed or lifted by an ADMIN (BACKLOG B-085).

## References

- [`src/main/java/com/educore/auth/LoginAttemptService.java`](../../src/main/java/com/educore/auth/LoginAttemptService.java)
- [`src/main/java/com/educore/auth/CaffeineBucketLoginRateLimiter.java`](../../src/main/java/com/educore/auth/CaffeineBucketLoginRateLimiter.java)
- [`src/main/java/com/educore/ipaccess/ClientAddress.java`](../../src/main/java/com/educore/ipaccess/ClientAddress.java)
- [`src/main/java/com/educore/ipaccess/IpAutoDenyService.java`](../../src/main/java/com/educore/ipaccess/IpAutoDenyService.java)
- [`src/main/resources/db/migration/V23__login_attempt_client_key.sql`](../../src/main/resources/db/migration/V23__login_attempt_client_key.sql)
- [`src/test/java/com/educore/auth/LockoutIT.java`](../../src/test/java/com/educore/auth/LockoutIT.java)
- [`src/test/java/com/educore/authz/LoginUnlockIT.java`](../../src/test/java/com/educore/authz/LoginUnlockIT.java)
- [`src/test/java/com/educore/auth/LoginIpv6ThrottlingIT.java`](../../src/test/java/com/educore/auth/LoginIpv6ThrottlingIT.java)
- [`src/test/java/com/educore/auth/CaffeineBucketLoginRateLimiterTest.java`](../../src/test/java/com/educore/auth/CaffeineBucketLoginRateLimiterTest.java)
- [`docs/ops/RUNBOOK_ADMIN_RECOVERY.md`](../ops/RUNBOOK_ADMIN_RECOVERY.md)
- Related: [0025](0025-perimeter-https-proxies-and-rate-limiting.md)
