# 0011. Apply the brand identity early and persist only the theme choice in `localStorage`

- Status: Accepted
- Date: 2026-10-02
- Original decisions: D-NEW-01, D-NEW-03

## Context

A new visual identity (`docs/brand/`) was available before the frontend platform migration was scheduled. The
frontend should also keep no session or personal data in browser storage, but a light/dark theme preference
that resets on every visit is a usability defect.

## Decision

- The visual identity from `docs/brand/` (tokens and brand rules) is applied to the frontend as soon as it is
  available, independently of the later structural move to React Router framework mode
  ([ADR 0003](0003-prerendered-public-site-and-spa.md)).
- The theme preference is the only value stored in `localStorage` (`frontend/app/lib/theme-storage.ts`);
  choosing `system` removes it. Access and session state stay in memory, and an ESLint rule forbids
  `localStorage` elsewhere.

## Consequences

Positive:

- A consistent look from the start; the theme survives reloads.
- The storage exception is a single, linted location holding a non-sensitive value.

Negative:

- The visual work was done on the old frontend structure and had to be carried over during the framework-mode
  migration.
- Strictly zero browser storage is not met; the exception must be kept in mind in privacy reviews.

## References

- [`docs/brand/BRAND_IDENTITY.md`](../brand/BRAND_IDENTITY.md)
- [`docs/brand/tokens.css`](../brand/tokens.css)
- [`frontend/app/lib/theme-storage.ts`](../../frontend/app/lib/theme-storage.ts)
- [`frontend/eslint.config.js`](../../frontend/eslint.config.js)
