# EduCore Attack Results

Release 1.0.0 · 2026-10-02. This document records what was attacked, how, and what held. It covers four
sources of evidence:

1. the attacker-mode test suite (JUnit tag `attack`);
2. the regression suite, many of whose tests use adversarial inputs;
3. an OWASP ZAP baseline scan of the production-shaped stack;
4. the static audit and the attack-chain analysis, with the fix status of every finding.

The risk ids (`R-nn`) and chain ids (`AC-nn`) refer to [`THREAT_MODEL.md`](THREAT_MODEL.md) and
[`ATTACK_CHAINS.md`](ATTACK_CHAINS.md). Abuse cases per actor are in [`ABUSE_CASES.md`](ABUSE_CASES.md).

## Summary

| Source | Scope | Result |
|---|---|---|
| Attacker-mode suite | 19 tests in `src/test/java/com/educore/attacks/` | 19 passed: every attack was refused |
| Regression suite | 1 144 tests (590 unit, 554 integration), 0 failures, errors or skips | Includes the adversarial-input classes listed below |
| OWASP ZAP baseline | Production-shaped stack: `prod` profile and TLS edge | 0 High, 0 FAIL-rule alerts; 1 Medium (false positive), 3 Low (dispositions in [`ZAP_RESULTS.md`](ZAP_RESULTS.md)) |
| Threat model and attack chains | 25 risks, 20 chains | 12 risks fixed, 3 mitigated, 10 open or partial with backlog ids; 13 chains fixed, 6 partial or open, 1 blocked from the start (section 4) |

No external penetration test has been carried out. The dynamic testing consists of the tests and the scan
described here.

## 1. Attacker-mode suite

The suite is excluded from the default build (`pom.xml` `excludedGroups=attack`) so that a succeeding attack
fails a dedicated CI job (`ci.yml` job `attacks`) rather than hiding among regression failures. Each test's
Javadoc describes the attack and the expected defence; a failing test means the attack succeeded.

```bash
./mvnw verify -Dgroups=attack -DexcludedGroups= -Djacoco.skip=true
```

Last recorded local run: 2026-10-02, **19 run, 0 failures, 0 errors, 0 skipped**. The local run did not pass
`-Djacoco.skip=true`, so Maven then stopped at the coverage check, which is meaningless for a 19-test subset; CI
skips JaCoCo for this job. That run predates the two security fix waves, which changed
`JwtAuthenticationFilter` (session epoch) and added `PasswordChangeRequiredScopeFilter`. CI runs the suite again
on every push, and it must also be re-run locally after the fix waves before tagging.

### `JwtTamperingIT` (13 tests)

| Attack | Defence that held |
|---|---|
| Unsigned token (`alg: none`) | Rejected; the request stays anonymous |
| Algorithm confusion: HS384 and HS512 instead of HS256 | Rejected |
| Token signed with a retired or unknown key | Rejected |
| Unknown `kid` and injection payloads in `kid` | Rejected without an error response leaking detail |
| Oversized token | Rejected before any cryptography |
| Missing expiration | Rejected |
| Expired token | Rejected |
| Future `nbf` (not before) | Rejected |
| Wrong issuer, wrong audience | Rejected |
| Valid signature, subject of a non-existent account | Treated as anonymous |
| Valid token of a deactivated account | Treated as anonymous |
| Valid token of a pending-deletion account | Confined to the restore-only scope: `GET /api/v1/me` answers, other routes are 403 `account/pending-deletion` |
| Control: a correctly signed token | Accepted (proves the negative cases are not trivially failing) |

### `PrivilegeEscalationIT` (6 tests)

| Attack | Defence that held |
|---|---|
| A token claiming ADMIN roles | The role is read from the database; the claim is ignored |
| A USER promotes themself or another account | 403 on the admin route |
| Every ADMIN write route called with a USER token | 403 for each route |
| `role` smuggled into the self-service profile body | Ignored; the DTO has no such field |
| HTTP verb tampering | The privileged handler is not reached |
| Method-override headers turning a read into a privileged write | No effect |

## 2. Regression tests with adversarial inputs

The regression suite (`./mvnw verify`, 1 144 tests, all passing at release) contains many tests whose inputs are
hostile by design. The counts are test methods; parameterised and matrix tests run more cases.

| Class | Attack exercised | Methods |
|---|---|---|
| `AuthorizationMatrixIT` | Every cell of `src/test/resources/rbac-matrix.csv` for anonymous, USER, ADMIN and restricted principals, including routes that must not exist | 7 |
| `PathVariantSecurityIT` | Path variants (trailing slash, case, encoded separators, matrix parameters) that try to bypass URL rules | 4 |
| `IdorIT` | Another account's id in paths and bodies of self-service and admin routes | 6 |
| `MassAssignmentIT` | Server-owned fields (`id`, `role`, `status`, `password`, `mustChangePassword`) in request bodies | 7 |
| `ValidationIT` | Markup, control and format characters, oversized values, type coercion (`{"role":0}`), malformed JSON | 22 |
| `ProblemDetailsIT` | Error paths that could leak stack traces, SQL or exception text; oversized bodies (413) | 21 |
| `RateLimitIT` | Floods of anonymous, authenticated and public requests; bucket keys | 9 |
| `ForwardedHeadersTomcatIT` | Spoofed `X-Forwarded-For` / `X-Forwarded-Proto` from untrusted peers | 6 |
| `ForwardedForThrottlingIT` | Login throttling with forged forwarding headers | 3 |
| `LockoutIT` | Lockout as a weapon: attacker network versus owner network, progressive delay 1/2/4/8/16/30/30 s | 7 |
| `LoginIpv6ThrottlingIT` | Address rotation inside one IPv6 /64 | 2 |
| `AutoDenyIT`, `IpAccessControlIT` | Repeated failed logins, MANUAL and AUTO deny rules, self-deny and trusted-proxy guards | 4, 15 |
| `PasswordChangeRequiredScopeIT` | API calls with a temporary or bootstrap password, bypassing the UI | 3 |
| `RefreshReuseDetectionIT`, `AuthConcurrencyIT` | Refresh-token replay; concurrent rotation, logout and password change | 2, 7 |
| `AccessTokenResidualValidityIT` | Use of an access token after logout and password change (documented residual) | 4 |
| `AccountLifecycleIT` | Pre-deletion tokens, restore without password, sessions across soft delete and restore, purge confirmation | 14 |
| `LastAdminGuardIT` | Concurrent demotion or deletion of the last administrators | 5 |
| `PseudonymDomainSeparationTest` | Login as `account:<id>` to de-pseudonymise a purged account | 3 |
| `ErasureLedgerRestoreIT` | Restore of an old dump (real `pg_dump`/`pg_restore`); the old refresh cookie and the purged account must not return | 2 |
| `DatabaseRolesIT` | Nine forbidden statements (DDL, `COPY ... PROGRAM`, extensions, role changes) through the application's own pool | 1 |
| `IngestionFencingIT` | Chunk writes and closes racing a lease expiry | 3 |
| `ImportUploadIT` | Two owners and recovery racing on staged uploads; invalid files | 8 |
| `IngestionDirectoryProtocolIT` | Links, swapped and half-written inbox files | 9 |
| `WebhookSsrfGuardTest` | Loopback, private, link-local, metadata, CGNAT, NAT64, mapped and `*.localhost` targets | 7 |
| `WebhookTransportTest` | Slow headers, endless bodies, redirects, blocking DNS resolvers past the deadline | 5 |
| `PublicApiLeakIT` | Unpublished courses and account fields on the anonymous surface | 5 |
| `ManagementEndpointSecurityIT` | Actuator endpoints without, or with a non-ADMIN, token | 6 |
| `CsvFormulaInjectionTest` | Spreadsheet formula triggers, including Unicode variants | 5 |
| `ArchitectureTest` | Runtime-built query strings reaching JPA or JDBC sinks (bytecode rule) | 9 |
| `JwtParserTest`, `RequestIdFilterTest`, `PiiMaskingTest` | Malformed tokens, unsafe request ids, secrets and personal data in log lines | 17, 8, 18 |

## 3. Dynamic scan (OWASP ZAP baseline)

On 2026-10-02 `scripts/zap-local.sh` scanned the production-shaped stack (`docker-compose.yml` +
`docker-compose.prod.yml`, `prod` profile, TLS edge with a throwaway certificate). The scan used a 3-minute spider
and the passive rules; it was not an authenticated or active scan. Results:

- **0 High and 0 FAIL-rule alerts.** The checks configured as FAIL passed: security headers (HSTS, CSP,
  `X-Frame-Options`, `X-Content-Type-Options`, Permissions-Policy), cookie flags, no version or debug headers, no
  stack traces, no mixed content, CORS without a wildcard, and no vulnerable JavaScript library.
- **1 Medium**, 10099 Source Code Disclosure: a false positive on English page copy.
- **3 Low:** COEP/CORP missing (accepted, improvement candidate); a private IP in the IP-allocation form's
  placeholder (false positive).

The nightly `zap.yml` workflow repeats the scan and fails on any High alert or FAIL rule. Details and
reproduction steps: [`ZAP_RESULTS.md`](ZAP_RESULTS.md).

## 4. Static audit and attack-chain findings

The threat model (STRIDE per component, read against the code) produced 25 risks and 20 multi-step attack
chains. Two fix waves followed. Status at release 1.0.0:

| Finding | Severity before | Status | Fixed by (code) | Pinned by (tests) |
|---|---|---|---|---|
| R-01 / AC-01 username-targeted lockout | Critical | FIXED | Per (username, network) lock, progressive delay, `unlock-login`, runbook | `LockoutIT`, `LoginUnlockIT` |
| R-02 / AC-03 dev seed promoted to production | High | FIXED | `DevSeedAccountGuard` | `FlywayProfileSwitchIT`, `DevSeedAccountGuardTest` |
| R-03 / AC-04 temporary password is a full credential | High | FIXED | `PasswordChangeRequiredScopeFilter` | `PasswordChangeRequiredScopeIT`, `AdminBootstrapIT` |
| R-19 / AC-05 SEO base URL binding | High | FIXED (did not reproduce; hardened) | Explicit placeholder, `ProdStartupGuard` https check | `EnvironmentVariableBindingTest`, `ProdStartupGuardIT` |
| R-04 / AC-06 IPv6 rotation past the login throttle | Medium | FIXED | `ClientAddress.clientKey`, admission login store | `LoginIpv6ThrottlingIT`, `CaffeineBucketLoginRateLimiterTest` |
| R-05 / AC-08 erased students in CSV files and archives | Medium | FIXED | `retain-processed-days` = 0, `IngestionRetention`, purge scrub | `IngestionRetentionIT` |
| R-06 / AC-09 restore resurrects accounts and sessions | Medium | FIXED | Erasure ledger, `post-restore.sh`, `ErasureLedgerReplay` | `ErasureLedgerRestoreIT`, `ErasureLedgerTest`, restore drill |
| R-16, R-20 / AC-10 pre-deletion token, soft delete keeps sessions | Medium | FIXED (ADMIN-override policy open) | Session epoch, password-protected restore | `AccountLifecycleIT` |
| R-21 / AC-12 de-pseudonymisation through a shared HMAC key | Medium | FIXED | Derived pseudonym key | `PseudonymDomainSeparationTest` |
| R-23 / AC-15 backend runs as the database superuser | Medium | MITIGATED (backup sidecar open) | DML-only runtime role, owner only for Flyway | `DatabaseRolesIT` |
| R-22 / AC-14 purge confirmation in edge logs | Low | FIXED | `POST .../purge` body, nginx JSON logs without query strings | `AccountLifecycleIT`, nginx canary check |
| R-24 / AC-16 recovery deletes a sibling's upload | Low | FIXED | `upload_staging` leases | `ImportUploadIT` |
| R-11 / AC-17 lease expiry does not fence writes | Low | MITIGATED (heartbeat executor open) | `IngestionFence` advisory lock | `IngestionFencingIT` |
| R-25 / AC-13 webhook DNS outside the deadline | Low | FIXED | DNS on a bounded executor inside the deadline | `WebhookTransportTest` |
| R-09 / AC-18 one compromised ADMIN locks out the rest | Medium | OPEN (recovery documented) | Runbook only | B-080 |
| R-10 / AC-07 load balancer collapses client addresses | High for such deployments | PARTIAL | Documented deployment constraint | B-081 |
| R-13 rate-limit store saturation by many /64 keys | Medium | PARTIAL | Admission store | B-082 |
| R-14 supply chain: wrapper checksum, npm scripts, unsigned images | Medium | PARTIAL | Pinning, Trivy, SBOMs | B-099 |
| R-07 / AC-11, R-08 / AC-13, R-12, R-15 / AC-20, R-17, R-18 | Low | PARTIAL | As analysed | B-096, B-097, B-098, B-100, B-102, B-103 |

The baseline audit's 27 findings (F-01 to F-27) and their final status are in
[`docs/audit/2026-10-02-final.md`](../audit/2026-10-02-final.md).

## 5. Residual risks

- **Single administrator control.** One compromised ADMIN can deny everyone else with complementary MANUAL rules
  and purge the other ADMINs. There is no second approval; recovery needs database access
  ([`RUNBOOK_ADMIN_RECOVERY.md`](../ops/RUNBOOK_ADMIN_RECOVERY.md), B-080).
- **Deployment shape.** nginx must be the first hop. Behind an unconfigured load balancer, all clients share one
  rate-limit bucket and one auto-deny counter (B-081).
- **Single instance.** Rate-limit buckets, auto-deny counters and IPv6 login denials are kept in memory per
  instance (B-051, B-085).
- **IPv6.** Deny rules and allocations are IPv4 only (B-050). A /48 holder can fill the rate-limit store with /64
  keys (B-082).
- **Token residual.** Access tokens survive logout and password change for at most 15 minutes (B-084).
- **Operations.** The backup sidecar still dumps as the owner role (B-091). Webhook events can be lost in a crash
  between commit and enqueue (B-030). nginx's error log can hold query strings of failed upstream calls (B-093).
- **Assurance gaps.** The ZAP scan was passive and unauthenticated. The attack suite has 19 tests, and its last
  local run predates the fix waves. No external penetration test, fuzzing campaign or source-composition review
  beyond Trivy and `npm audit` has been carried out.
