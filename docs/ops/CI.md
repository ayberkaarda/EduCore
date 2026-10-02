# CI, Supply Chain and Release Checks

Security checklist items 19 (dependencies, SBOMs, images) and 23 (attack tests, DAST). Workflows live in
`.github/workflows/`, and `actionlint` must report no findings for any of them.

## Workflows

| Workflow | Trigger | Jobs |
|---|---|---|
| `ci.yml` | push to `main` and `release/**`, tags `v*`, pull requests, manual | `backend`, `attacks`, `frontend`, `security` (calls `supply-chain.yml`), `e2e`, `docker` (details below) |
| `supply-chain.yml` | called by `ci.yml`; weekly (Mon 03:17 UTC) re-scan; manual | `secrets`: gitleaks over the full history and the working tree. `backend-dependencies`: Maven resolve, CycloneDX SBOM (`sbom-maven`), Trivy `fs` gate. `frontend`: `npm ci`, CycloneDX SBOM (`sbom-npm`), `npm audit --omit=dev --audit-level=high`. `images` (backend with its tests, frontend, backup sidecar): build, non-root check, Trivy `image` gate. |
| `zap.yml` | nightly 03:30 UTC, push to `release/**`, manual | OWASP ZAP baseline against the production-shaped stack (prod profile, TLS edge with a throwaway certificate). Fails on any High alert or FAIL rule. |
| `backup-verify.yml` | nightly 02:40 UTC, manual, PRs touching backup files | Compose stack with throwaway secrets, sidecar `backup-now`, `scripts/backup/verify-latest.sh` (see BACKUP_RESTORE.md). |

### `ci.yml` jobs

| Job | What it proves |
|---|---|
| `backend` | `./mvnw verify` on JDK 21. Runs unit tests, Testcontainers integration tests, ArchUnit rules, the OpenAPI drift check (`OpenApiDocumentIT`) and the JaCoCo gate (below). Uploads the coverage report. |
| `attacks` | `./mvnw verify -Dgroups=attack -DexcludedGroups= -Djacoco.skip=true`: the attacker-mode suite (`src/test/java/com/educore/attacks/**`), excluded from the default build. A failing test means an attack succeeded (each test's Javadoc describes the attack and the expected defence). |
| `frontend` | `npm ci`, lint, typecheck, unit tests, `npm run build:mock`, then Lighthouse CI with the `frontend/lighthouserc.cjs` budgets. |
| `security` | The `supply-chain.yml` jobs above. |
| `e2e` | `docker compose up --wait` (dev profile, seeded data, ephemeral secrets), then `npm run test:e2e` (Playwright) against http://localhost:3000. The job skips the Playwright step until `frontend/package.json` defines `test:e2e`. |
| `docker` | Builds the backend and frontend images with buildx. On a `v*` tag it pushes the backend image to GHCR (`ghcr.io/<owner>/educore-backend`) with provenance (`mode=max`) and SBOM attestations. Only this job has `packages: write`, and the registry login uses the job's `GITHUB_TOKEN`. The frontend image is never pushed (see "Frontend builds"). |

### JaCoCo gate (enforced by the build)

`pom.xml` binds `jacoco:check` to `verify` and reads the merged unit and integration data. Every package under
`com.educore.auth`, `com.educore.security`, `com.educore.ipaccess` and `com.educore.ingestion` must keep at least
85 % instruction coverage and 55 % branch coverage.

Coverage measured on 2026-10-02 (instructions / branches):

| Package | Instructions | Branches |
|---|---|---|
| auth | 95.2 % | 85.6 % |
| security | 97.7 % | 84.4 % |
| security.audit | 99.6 % | 71.4 % |
| ipaccess | 96.5 % | 87.5 % |
| ingestion | 92.0 % | 83.6 % |
| ingestion.batch | 86.1 % | 59.5 % |
| whole code base | 93.8 % | 80.9 % |

The thresholds sit below the measured values so that ordinary changes pass. Lowering them requires a
reviewed change to `pom.xml`.

### Frontend builds

The public pages are prerendered from the public API at build time, and the build refuses an unreachable API
or an empty catalog (docs/seo/BUILD.md). Each place therefore uses the build that matches what it needs:

| Where | Build | Why |
|---|---|---|
| `ci.yml` `frontend`, Lighthouse | `npm run build:mock` (bundled mock API) | No backend in the job; checks prerendering, SEO rules and budgets. |
| `ci.yml` `e2e`, `docker-compose.yml` | image arg `BUILD_SCRIPT=build:mock`, `PUBLIC_SITE_URL=http://localhost:3000` | The image is built before the backend exists; development and tests only. |
| `supply-chain.yml` image scan, `ci.yml` `docker`, `zap.yml` | image arg `BUILD_SCRIPT=build:mock` | Scans and DAST cover the image and the edge, not the catalog content; this image is never pushed. |
| `docker-compose.prod.yml` | `BUILD_SCRIPT=build` (fixed), real API at `EDUCORE_PUBLIC_API_URL` / `EDUCORE_SEO_BASE_URL` | Production must ship the real catalog; the build fails rather than ship an empty one. The first installation is described in docs/ops/TLS.md. |

### DAST (OWASP ZAP baseline)

`zap/baseline.conf` lists every passive rule with an action:
- **FAIL:** guarantees the edge and the API already give (headers, cookie flags, CSP, no stack traces or version
  leaks), so a regression fails the scan.
- **IGNORE:** informational rules only, each with a written reason.
- **WARN:** findings that need a human decision.

`scripts/zap-local.sh [URL]` runs the same scan locally. The scan also fails on any High-risk alert, whatever
the configuration says. Findings and their dispositions are in docs/security/ZAP_RESULTS.md.

### Release checks

- `scripts/preflight.sh --env-file .env` runs on the production host before `up`. It checks:
  - required variables and placeholder values;
  - secret lengths: JWT at least 32 decoded bytes, pepper at least 32 characters, AES-256 key exactly 32
    bytes, database password at least 16, bootstrap password at least 12;
  - that the public origin is https and resolves in DNS;
  - that the certificate is valid for more than 14 days;
  - free ports, `docker compose config -q` and free disk space.

  Exit 0 = pass, 1 = a check failed, 2 = usage error. Secret values are never printed.
- `scripts/check-env-docs.sh` checks:
  - every variable read by the compose files and by Spring placeholders is in `.env.example`;
  - every documented key is still read somewhere;
  - every key has an explanatory comment.

## Branch protection (recommended settings for `main` and `release/**`)

- Require a pull request with at least one approving review from someone other than the author. Dismiss
  stale approvals on new commits, and require review from code owners when a `CODEOWNERS` file exists.
- Required status checks, which must pass on the latest commit (enable "require branches to be up to date"):
  - `ci / Backend (mvn verify, JaCoCo gate)`
  - `ci / Attacker-mode suite (JUnit tag attack)`
  - `ci / Frontend (lint, typecheck, test, build, Lighthouse)`
  - every job of `ci / Security (gitleaks, Trivy, npm audit, SBOMs, image scans)`
  - `ci / End-to-end (compose stack, Playwright)`
  - `ci / Images (build; push the backend to GHCR on tags)` for both matrix entries
- On `release/**`, also require the latest `zap` run on the branch to be green before tagging.
- Do not allow force pushes or branch deletion, include administrators, and require linear history.
- Signed commits are optional. Enable "require signed commits" once every maintainer has a signing key.
- Create `v*` tags only from a green `release/**` or `main` commit. A tag build publishes the backend image.

All third-party actions are pinned by commit SHA (version in a trailing comment); tool images (Trivy,
socat) and every Dockerfile/compose base image are pinned by digest. `.github/dependabot.yml` opens weekly
PRs for Maven, npm (`/frontend`), Docker (`/`, `/frontend`, `/infra/backup`), docker-compose and GitHub
Actions; minor and patch updates are grouped. Workflows run with `permissions: contents: read` and
`persist-credentials: false`.

## Dependency scanner: Trivy (chosen over OWASP dependency-check-maven)

Decision: **Trivy `fs`** for dependencies, **Trivy `image`** for containers.

| Criterion | Trivy | dependency-check-maven |
|---|---|---|
| Ecosystems | Maven (`pom.xml` + `~/.m2`), npm lockfile, OS packages in images | Maven only (npm via a separate experimental analyzer) |
| Data source | GitHub Advisory DB + vendor/NVD data, one ~60 MB DB download | NVD feed; needs an NVD API key and a multi-minute initial sync, frequent rate-limit failures |
| Image scanning | Same tool, same suppression file | Not available; a second tool would be needed |
| False positives | Matches on package coordinates (purl) | CPE matching, a known source of false positives in Spring projects |
| Runtime | Seconds after the DB download | Minutes, plus cache management |

One tool and one suppression file cover all three surfaces (backend dependencies, frontend lockfile,
both images), which keeps the policy in one place. Gate: `--severity HIGH,CRITICAL --exit-code 1`, which
corresponds to CVSS >= 7.0. The fs scan covers runtime dependencies only (Trivy excludes npm dev
dependencies and Maven test scope by default); `npm audit --omit=dev` is the npm registry's own second opinion.
The image scan uses `--ignore-unfixed`: an OS package finding without a published fix cannot be acted on and
would block every build; it fails as soon as a fix exists. The frontend runtime image runs `apk upgrade` so
fixes published after the pinned digest are applied at build time.

Maven Central rate-limits anonymous POM downloads (HTTP 429, IP blocked for 30 minutes). The workflow
therefore resolves dependencies with Maven first and mounts `~/.m2` into the Trivy container; for a local
run add `--offline-scan`:

```bash
docker run --rm -v "$PWD:/src:ro" -v "$HOME/.m2:/root/.m2:ro" aquasec/trivy:0.75.0 fs \
  --scanners vuln --severity HIGH,CRITICAL --offline-scan \
  --ignorefile /src/.github/trivy/trivyignore.yaml --skip-dirs /src/frontend/node_modules /src
```

### Suppressions

File: `.github/trivy/trivyignore.yaml`. Rules: a finding is suppressed only when EduCore is demonstrably
not affected; every entry has a written `statement` and an `expired_at` date (at most one quarter ahead);
after expiry the build fails again. Entries are removed as soon as an upgrade removes the vulnerable version.
Suppressions are reviewed in the PR that adds them like any code change.

## SBOMs

| SBOM | Command | Artifact |
|---|---|---|
| Backend | `./mvnw cyclonedx:makeBom` (`cyclonedx-maven-plugin` 2.9.3, runtime scope, CycloneDX 1.6 JSON) writes `target/bom.json` (`${buildDirName}/bom.json`) | `sbom-maven` |
| Frontend | `npx --no-install cyclonedx-npm --omit dev --output-format JSON --output-file bom.npm.json` (`@cyclonedx/cyclonedx-npm`, devDependency) | `sbom-npm` |

## Images

Both images are multi-stage, built from source, pinned by digest and run as non-root:

| Image | Build stage | Runtime stage | User |
|---|---|---|---|
| `Dockerfile` (backend) | `maven:3.9-eclipse-temurin-21` | `eclipse-temurin:21-jre-alpine` | uid 10001 `educore` |
| `frontend/Dockerfile` | `node:22-alpine`, `npm ci`, `npm run build` (output `dist/client`) | `nginxinc/nginx-unprivileged:1.30-alpine`, port 8080 | uid 101 `nginx` |
| `infra/backup/Dockerfile` | n/a | `postgres:15-alpine` + supercronic + rclone (Alpine packages), base image's `gosu` removed | uid 70 `postgres` |

The backup sidecar deletes `/usr/local/bin/gosu` from the `postgres` base image: the base ships it only for the
root-to-`postgres` step-down in `docker-entrypoint.sh`, which the sidecar never runs (`USER postgres`, own entrypoint).
gosu 1.19, the latest upstream release, is built with go1.24.6 and carries Go standard-library CVEs (e.g.
CVE-2025-68121, CVE-2026-56860 `net/url`, CVE-2026-56862 `crypto/tls`) that failed the image gate. supercronic
(0.2.49) and rclone (1.74.1) come from Alpine 3.24 packages, rebuilt by Alpine with a current Go, and scan clean; no
suppression was added. Revisit if the sidecar ever needs to start as root.

`.dockerignore` in the repository root (allow-list: `pom.xml`, `src/`; `.env`/`.env.*` excluded even under `src/`) and in
`frontend/` (excludes `node_modules`, build output and every `.env`/`.env.*` except `.env.example`) keep the contexts
small and free of secrets. Build-time frontend settings are passed as build arguments, never as env files.

### Backend tests inside the image build

The backend Dockerfile runs `mvn verify` unless `--build-arg SKIP_TESTS=true`. The default is
`SKIP_TESTS=false` so that an image cannot be produced from code whose tests fail. The integration tests
use Testcontainers and need a Docker daemon, which a `RUN` step cannot reach through a socket mount
(BuildKit's SSH forwarding was tried and does not carry the Docker API). CI therefore:

1. starts `alpine/socat` on the host network, listening on a random `127.0.0.1` port and forwarding to `/var/run/docker.sock`;
2. builds with `docker buildx build --network host --allow network.host --build-arg TESTCONTAINERS_DOCKER_HOST=tcp://127.0.0.1:<port>`;
3. removes the proxy when the step ends (the runner is ephemeral and the port is loopback-only).

Inside the build `DOCKER_HOST` and `TESTCONTAINERS_HOST_OVERRIDE=localhost` are set only for the Maven
command; the argument exists only in the build stage, not in the runtime image. This requires a Linux
Docker host: on Docker Desktop the daemon is reachable this way but published container ports are not
routed to a host-network build, so local image builds use `SKIP_TESTS=true` and tests run with `./mvnw verify`
on the host instead. Without the daemon argument a `SKIP_TESTS=false` build stops with an explicit message.

`docker-compose.yml` passes `SKIP_TESTS: "true"` as a build argument, so `docker compose up --build` works
on any machine; CI is the place where the image is built with tests.

## Local equivalents

| Check | Command |
|---|---|
| Production preflight | `scripts/preflight.sh --env-file .env` |
| Env documentation | `scripts/check-env-docs.sh` |
| DAST | `scripts/zap-local.sh https://localhost` (or `ZAP_NETWORK=<compose network> scripts/zap-local.sh https://educore-frontend:8443`) |
| Attack suite | `./mvnw verify -Dgroups=attack -DexcludedGroups= -Djacoco.skip=true` |
| Workflows | `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint` |
| Shell scripts | `docker run --rm -v "$PWD:/mnt" -w /mnt koalaman/shellcheck:stable scripts/backup/*.sh infra/backup/bin/*.sh` |
| Compose | `docker compose --env-file <file with dummy values> config -q` |
| Terraform | see COST_GUARDRAILS.md |
| Alert rules | `docker run --rm -v "$PWD/infra/monitoring:/w" --entrypoint promtool prom/prometheus check rules /w/alerts.example.yml` |
