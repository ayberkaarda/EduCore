# End-to-end tests (Playwright)

The end-to-end suite in `frontend/tests/e2e` drives a real browser against the running compose stack through the
edge only: nginx on `http://localhost:3000` serves the public site and the SPA and proxies `/api/` to the backend.
Nothing is mocked; every test creates its own data (unique names, student numbers and IP ranges) through the admin
API or the UI, so the suite can run repeatedly against the same database.

## Running

```bash
# 1. Stack (dev profile with the synthetic demo seed). The .env needs every variable docker-compose.yml marks as
#    required: EDUCORE_DB_NAME, EDUCORE_DB_USERNAME, EDUCORE_DB_PASSWORD, EDUCORE_DB_APP_USERNAME,
#    EDUCORE_DB_APP_PASSWORD, EDUCORE_JWT_SECRET, EDUCORE_LOGIN_PEPPER, EDUCORE_ENCRYPTION_KEY.
docker compose up -d --build --wait

# 2. Tests (the stack must already be up)
cd frontend
npm ci
npm run test:e2e
```

| Variable | Default | Meaning |
|---|---|---|
| `E2E_BASE_URL` (or `BASE_URL`) | `http://localhost:3000` | Edge origin of the stack |
| `E2E_CHANNEL` | `chrome` locally, `chromium` when `CI` is set | `chrome` / `msedge` use the installed browser (no download); `chromium` needs `npx playwright install chromium` |
| `E2E_ADMIN_USERNAME`, `E2E_ADMIN_PASSWORD` | the seeded `admin` and the dev seed's public demo password | ADMIN used by the tests; set both for any stack that is not the dev seed |

The tests run serially (`workers: 1`): they share one backend, and its login limit of 10 attempts per client IP and
minute applies to all of them. When a sign-in answers 429, the helpers wait for the login button to be enabled again
(the UI's own Retry-After countdown) or poll the login endpoint; no test uses a fixed sleep. A full run takes about
30 seconds, or up to two minutes when the login bucket runs dry.

Failures keep a trace, a screenshot and the HTML report: `npx playwright show-report` and
`npx playwright show-trace test-results/<test>/trace.zip`. `playwright-report/` and `test-results/` are git-ignored.
CI runs the same suite in the `e2e` job of `.github/workflows/ci.yml` and uploads `playwright-report` on failure.

## Coverage

| Spec | Flow |
|---|---|
| `student-onboarding.spec.ts` | ADMIN signs in, the dashboard loads, ADMIN adds a student; the temporary password dialog shows the username, ignores Escape and backdrop clicks and is gone for good once closed. The student signs in with the temporary password, is held on the change-password screen, and every API route except the session endpoints answers 403 `account/password-change-required`; after the change the student lands in the app. |
| `access-control.spec.ts` | A USER opening `/app/users` sees the access-denied panel; the admin API answers 403 problem details to the same access token. |
| `enrollment.spec.ts` | ADMIN enrols a student into a course from the student's course page and drops the enrolment again (with confirmation); the API confirms both states. |
| `session.spec.ts` | The session survives a reload through the HttpOnly refresh cookie (rotated on each refresh); sign-out clears it, a reload stays signed out, and the old cookie is refused by the server. |
| `account-deletion.spec.ts` | A USER schedules account deletion with the password, is sent to the login screen, signs in to the "Deletion scheduled" screen that offers only Restore and Sign out (the API answers 403 `account/pending-deletion` elsewhere), and restores the account. |
| `csv-import.spec.ts` | ADMIN uploads a CSV built from `csv_uploads/sample/students.sample.csv` (own student numbers) on the import page; its job log reaches Success or Partial. |
| `ip-rules.spec.ts` | ADMIN adds a CIDR deny rule outside the client's address (TEST-NET-2), sees it listed, is refused a self-deny (409 `ip-rule/self-deny` with its message), and deletes the rule. |
| `public-site.spec.ts` | `/`, `/en/` and `/courses` render without script or console errors, with the expected `<title>`, `lang`, canonical link and parseable JSON-LD. |

## Public site in the compose image

The compose file builds the frontend image with `BUILD_SCRIPT=build:mock`: the public pages are prerendered from the
bundled mock catalog, because the backend does not exist yet while the image is built. The public-site tests check
structure (titles, JSON-LD, no errors), which holds for both catalogs. The production image
(`docker-compose.prod.yml`) builds with `BUILD_SCRIPT=build` from the real public API and fails the build when the
API is unreachable or no course is published (`docs/seo/BUILD.md`).

To build the public site from a running stack's real catalog (at least one course must be published, i.e. have a
slug, a description and `published: true`):

```bash
cd frontend
PUBLIC_API_URL=http://localhost:3000 PUBLIC_SITE_URL=http://localhost:3000 npm run build
```

`PUBLIC_SITE_URL` must equal the backend's `educore.seo.base-url` (`EDUCORE_SEO_BASE_URL`, default
`http://localhost:3000`); the build compares it with `/api/v1/public/site-facts`. Lighthouse CI then audits that
build: `LHCI_COURSE_SLUG=<published slug> CHROME_PATH=<chrome> npm run lighthouse`.

## README screenshots

`tests/e2e/screenshots.spec.ts` refreshes `screenshots/*.png` (1440x900, light theme) from a running stack. It adds
demo data through the admin API only when missing (the two sample CSV imports, three deny rules, two webhooks) and is
not part of `npm run test:e2e`:

```bash
cd frontend
npx playwright test --project=screenshots
```

Run it on a freshly started stack so that no test data shows up in the pictures.
