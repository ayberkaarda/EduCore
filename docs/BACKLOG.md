# EduCore Backlog

Append-only log of findings discovered outside the active phase (rule R4). Add new rows at the bottom; never edit or delete existing rows. To close an item, append a new row that references the original ID and states the resolving phase or commit.

Severity: **C** critical · **H** high · **M** medium · **L** low.

| ID | Date | Found in | File | Sev | Rationale |
|---|---|---|---|---|---|
| B-001 | 2026-10-01 | P0 | `.env.example:21` | M | The `EDUCORE_JWT_SECRET` format sample is a 68-character base64 string, so `gitleaks detect --no-git` reports it as `generic-api-key` and the P0 acceptance criterion "0 findings" fails; leave the value empty or mark the line `# gitleaks:allow`. |
| B-002 | 2026-10-01 | P0 | `frontend/.neon`, `frontend/skills-lock.json` | L | Neon CLI init marker and agent-skill lock file are unrelated to the build (F-20); delete when the frontend is reworked in P8. |
| B-003 | 2026-10-01 | P0 | `frontend/README.md` | L | Still the Vite template text (F-20); replace with project-specific frontend docs. |
| B-004 | 2026-10-01 | P0 | `frontend/Dockerfile`, `Dockerfile` | M | Frontend image uses `npm install` instead of `npm ci`; backend image builds with `-DskipTests`, so broken tests never block an image (F-20). |
| B-005 | 2026-10-01 | P0 | `screenshots/loginPage.jpeg`, `screenshots/maindash.jpeg` | L | Committed screenshots of the running app were not reviewed for personal data; review, and replace with screenshots of synthetic seed data if needed. |
| B-006 | 2026-10-01 | P0 | `frontend/src/Login.jsx`, `src/main/java/com/example/project3/config/DataSeeder.java` | L | Login page shows a demo username derived from the author's name together with the default password; rename the seeded user and remove the hint together (P1 seeder rewrite plus P8). |
| B-007 | 2026-10-01 | P0 | `README.md` | L | Contains the author's name and claims network-level IP blocking that does not exist (F-14); rewrite in P10. |
| B-008 | 2026-10-01 | P0 | `docker-compose.yml` | L | `docker compose config` warns that the top-level `version` attribute is obsolete. |
| B-009 | 2026-10-01 | P0 | `src/main/java/com/example/project3/config/FileIntegrationConfig.java` | M | The poller routes files by name (`ogrenci`/`student`, `course`/`ders`) from the `csv_uploads` root; P0 added `csv_uploads/{inbox,processing,done,failed}/` and `csv_uploads/sample/`, which the current flow does not use. Wire the inbox/processing/done/failed lifecycle in P6. |
| B-010 | 2026-10-01 | P0 | `.gitignore` (root) | L | Git ignore behaviour of the new `csv_uploads/**` negations was reasoned from gitignore rules, not checked with `git check-ignore` (no git commands in P0); verify with `git status --ignored csv_uploads` before committing. |
| B-011 | 2026-10-01 | P0 | `frontend/src/App.jsx` | M | `/users` and `/profile` screens exist as components but have no route or navigation entry. |
| B-012 | 2026-10-01 | P0 | `frontend/src/Login.jsx`, `frontend/src/App.jsx` | H | The frontend stores authentication tokens in `localStorage`; covered by P2/P8. |
| B-013 | 2026-10-01 | P0 | `frontend/src/WeatherWidget.jsx` | L | The weather widget shows backend Turkish text in an English UI. |
| B-014 | 2026-10-01 | P0 | `docker-compose.yml` | L | Host port 5432 conflicts with other local PostgreSQL containers; consider making the host port configurable. |
| B-015 | 2026-10-01 | P0 | `.gitleaks.toml` | L | Gitleaks cannot flag one-character literal passwords. |
| B-016 | 2026-10-01 | P2 | `src/main/java/com/educore/service/AccountCredentialService.java`, `config/BatchConfig.java`, `service/StudentMultiThreadService.java` | M | Admin password reset endpoint: CSV-imported students get a random temporary password that is stored only as a hash and never returned or logged, so they cannot log in until an ADMIN resets their password; add an ADMIN-only reset endpoint that issues a new temporary password once (P3 account admin). |

| B-017 | 2026-10-01 | P2 | `src/main/java/com/educore/service/AccountCredentialService.java` | M | `assignTemporaryPassword` sets the password hash and `mustChangePassword` flag but does not end existing sessions; when the ADMIN reset endpoint (B-016) is built, lock the account, use `AccountLocks.compareAndSetPassword` and revoke all refresh tokens in one transaction. |
| B-018 | 2026-10-01 | P2 | `src/main/java/com/educore/auth/LoginAttempt.java`, `src/main/java/com/educore/auth/SecurityEvent.java` | L | Add retention/purge for `login_attempt` and `security_event` in P7. |
| B-019 | 2026-10-01 | P2 | `frontend/src` | M | Add a real change-password screen for accounts with `mustChangePassword`; the frontend currently shows only a notice (P8). |
