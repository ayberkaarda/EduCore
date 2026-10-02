# EduCore frontend

React 19 and React Router 7 in framework mode (`ssr: false`), built with Vite. Decision D-03 (option A): the public
route `/` is prerendered to static HTML at build time; everything under `/app` is a client-rendered single-page
app. There is no Node runtime in production.

## Commands

| Command | What it does |
|---|---|
| `npm run dev` | Dev server on http://localhost:3000 with `/api` proxied to the backend |
| `npm run build` | `react-router build`, then `scripts/externalize-inline-scripts.mjs`; output in `dist/client` |
| `npm run preview` | Serves the build on port 3000 with the same `/api` proxy |
| `npm run lint` | ESLint (TypeScript, React hooks, jsx-a11y, security rules) |
| `npm run typecheck` | `tsc` |
| `npm run test` | Vitest + React Testing Library + MSW (`tests/`) |

Port 3000 matters: it is the origin the backend's `dev` profile allows for CORS and for the `Origin` check on
`/api/v1/auth/refresh` and `/api/v1/auth/logout`.

## API base URL

All API paths in the code are `/v1/...`, resolved against `VITE_API_BASE_URL` (default `/api`):

- **Proxied (default).** The browser calls `/api/v1/...` on its own origin. `vite.config.ts` forwards `/api` to
  `EDUCORE_DEV_PROXY_TARGET` (default `http://localhost:8080`; use `http://localhost:8081` for the
  docker-compose backend). In production nginx proxies `/api/` to the backend, so no CORS is needed.
- **Direct.** `VITE_API_BASE_URL=http://localhost:8081/api` calls the backend cross-origin; the backend must allow
  the page origin (`EDUCORE_CORS_ALLOWED_ORIGINS`).

Every key is documented in `.env.example`.

## Build output

- `dist/client/index.html`: the prerendered landing page (`/`).
- `dist/client/__spa-fallback.html`: the shell for every `/app/**` path (serve it as the SPA fallback).
- `dist/client/assets/`: hashed JS, CSS and fonts (safe to cache as immutable).

React Router writes inline `<script>` tags into the generated HTML. `scripts/externalize-inline-scripts.mjs`
moves each one into `dist/client/assets/inline-<sha256>.js` at the same position, so the pages run under
`script-src 'self'` without `'unsafe-inline'`. The app sets no inline `style` attributes and injects no
`<style>` elements (toasts are plain CSS), so `style-src 'self'` works as well.

## Structure

```
app/
  root.tsx               document layout, query client, toast region
  routes.ts              route table (/, /app/*, redirects from the pre-P8 paths)
  routes/                route modules (thin: guards + one screen each)
  lib/                   api.ts (axios + refresh), api-error.ts, toast.ts, query-client.ts, theme-storage.ts
  components/            shared UI (AppShell, Dialog, TextField, Toaster, ThemeToggle, ...)
  features/<feature>/    api.ts, hooks.ts, schemas.ts (zod), components/
tests/                   Vitest suites and MSW handlers
```

Features: `auth`, `students`, `courses`, `enrollments`, `job-logs`, `ip-rules`, `users`, `weather`.

## Session handling

- The access token lives in memory only (`app/lib/api.ts`). Nothing about the session (token, role, name,
  `mustChangePassword`) is written to Web Storage; the only stored value is the colour theme
  (`app/lib/theme-storage.ts`). ESLint fails on any other `localStorage`/`sessionStorage` use.
- On load, `AuthProvider.bootstrap()` calls `POST /api/v1/auth/refresh` (HttpOnly cookie) and then
  `GET /api/v1/auth/me`, so a reload keeps the user signed in.
- A `401` on any other call triggers one shared (single-flight) refresh and a single retry; if that fails the
  session ends and the user is sent to `/app/login`.
- Only the auth calls (login, refresh, logout, password change) send cookies (`withCredentials`).
- `RequireAuth` / `RequireRole` mirror `docs/security/RBAC_MATRIX.md`; the API enforces the same rules.
- A user with `mustChangePassword` is held on `/app/change-password` until the password is changed.

## Routes

`/` (static), `/app/login`, `/app` (dashboard; USER sees the profile), `/app/profile`, `/app/courses`,
`/app/change-password`, and ADMIN-only `/app/students`, `/app/students/:id`, `/app/users`, `/app/logs`,
`/app/ip-rules`. The old paths (`/login`, `/students`, `/ips`, ...) redirect in the browser.
