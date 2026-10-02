# Security Policy

Thank you for helping keep EduCore and its users safe. This document explains how to report a vulnerability,
what is in scope, and what you can expect from us.

## Supported versions

Security fixes are provided for the current `1.0.x` release line only.

| Version | Supported |
|---|---|
| 1.0.x | Yes |
| < 1.0 | No (pre-release; upgrade to 1.0.x) |

## Reporting a vulnerability

Report privately. **Do not open a public issue, pull request or discussion for a suspected vulnerability**, and
do not disclose it elsewhere until a fix has been released and coordinated with you.

Use GitHub's private vulnerability reporting for this repository:

1. Go to the **Security** tab of `https://github.com/ayberkaarda/EduCore`.
2. Choose **Report a vulnerability** (GitHub Security Advisories, "Privately report a vulnerability").
3. Describe the issue (see "What to include" below). The report and the discussion stay private to you and the
   maintainers until the advisory is published.

If private reporting is unavailable to you, open a minimal public issue that contains **no technical detail**,
asking a maintainer to open a private channel, and withhold the specifics until they respond.

Please do not use e-mail: this project configures no security mailbox, so a dedicated address would not be
monitored.

### What to include

- the affected version or commit, and the environment (profile, deployment);
- a clear description of the vulnerability and its impact;
- step-by-step reproduction, including requests, inputs and any configuration;
- a proof of concept where possible;
- any suggested remediation.

Never include real personal data, real credentials or secrets in a report. Redact them and describe the shape
instead.

## Response targets

These are targets, measured in business days, not contractual guarantees:

| Stage | Target |
|---|---|
| Acknowledge receipt | within 3 business days |
| Initial assessment and severity | within 7 business days |
| Fix or mitigation plan with a timeline | within 30 days for High/Critical |
| Public advisory and credit | after a fix is released, coordinated with you |

We will keep you informed of progress and let you know when the issue is resolved. With your permission we
credit you in the advisory; tell us the name or handle to use, or ask to remain anonymous.

## Scope

In scope: the code in this repository — the backend (`src/main/java/com/educore/**`), the frontend
(`frontend/`), the reverse-proxy and deployment configuration (`infra/`, `docker-compose*.yml`, `Dockerfile*`),
the database migrations (`src/main/resources/db/migration`) and the CI/CD workflows (`.github/`).

Examples of in-scope issues: authentication or authorization bypass, injection, SSRF, insecure direct object
references, secret exposure, missing or incorrect access control, cryptographic mistakes, denial of service
reachable by an unauthenticated or low-privileged user, and supply-chain weaknesses in the build.

Out of scope:

- the security of a deployment's host, Docker daemon, operating system or network — EduCore trusts the host
  operator and the first reverse-proxy hop (see `docs/security/THREAT_MODEL.md`, section 3);
- findings that require control of the host, the `.env` file, the Docker socket, the database or the TLS private
  key (these are a trust boundary we do not defend against);
- denial of service by raw traffic volume, handled upstream of the application;
- vulnerabilities in third-party services (GitHub, Maven Central, npm, cloud providers) themselves;
- the example values in `.env.example`, `docs/` and test fixtures, which are synthetic by design;
- reports produced only by automated scanners without a demonstrated, reachable impact in this application;
- missing security headers or configuration on a site that is not this project's reference deployment.

Known, already-tracked limitations are listed in `docs/BACKLOG.md` and in `docs/security/THREAT_MODEL.md`;
re-reporting them is welcome only if you can show a new impact.

## Safe harbour

We will not pursue or support legal action against you for security research conducted in good faith that:

- respects this policy and reports the issue privately and promptly;
- stays within the scope above and uses only accounts and data you own or that a maintainer authorises;
- avoids privacy violations, data destruction, service degradation and access to or modification of other
  users' data beyond the minimum needed to demonstrate the issue;
- does not exfiltrate real personal data, and deletes any such data encountered accidentally;
- gives us reasonable time to remediate before any disclosure.

Work conducted consistently with this policy is considered authorised, and we will work with you to understand
and resolve the issue quickly. If in doubt about whether a specific action is in scope or authorised, ask first
through the private report. This safe harbour does not waive the rights of third parties who are not bound by
this policy.

## After a fix

Fixes are released on the `1.0.x` line with a GitHub Security Advisory describing the issue, the affected
versions, the fix and any workaround, and crediting the reporter unless anonymity was requested. Operators
should upgrade promptly; advisories note when rotating a secret or other operator action is required (see
`docs/security/KEY_ROTATION.md` and `docs/security/SECRET_ROTATION_AND_HISTORY_PURGE.md`).
