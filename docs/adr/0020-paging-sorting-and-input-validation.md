# 0020. Strict paging, whitelisted sorting and allow-list input validation

- Status: Accepted
- Date: 2026-10-02
- Original decisions: D-NEW-13, D-NEW-14

## Context

Out-of-range paging parameters were silently clamped, sorting by arbitrary properties had been removed
([ADR 0016](0016-admin-only-listings.md)) without a safe replacement, and request DTOs accepted markup and
control characters in names and other text fields.

## Decision

Paging and sorting:

- Out-of-range `page`/`size` are rejected with 400 `request/invalid` (`Paging`), not clamped.
- The two account listings accept a whitelisted `sort` parameter (`SortWhitelist`); an unknown key or direction
  is 400 `sort/invalid`. The whitelist maps client keys to entity properties, so raw input never reaches
  `Sort.by`. Course, IP rule, job log and security event listings keep a fixed order without `sort`.

Input rules (`common/validation/InputPatterns` and `@Size` bounds on the request records):

- Person names start with a letter and contain letters, combining marks, space, `'`, `.`, `-` (at most 100).
- `studentNumber` matches `^[0-9]{4,12}$`; usernames are 3 to 100 letters, digits, `.`, `_`, `-`.
- IPv4 addresses are dotted quads without leading zeros.
- Course `name` at most 150, `term` at most 50, `instructor` at most 100, without control characters;
  `search` at most 100 without control characters; path ids are positive.
- Optional fields accept the empty string as "none"; `UpdateStudentRequest.firstName` is required.

## Consequences

Positive:

- Invalid input is stopped at the boundary with a precise problem code.
- The frontend (sizes 5, 10, 50 and `asc`/`desc`) is unaffected.

Negative:

- Stricter name rules may reject unusual but legitimate names; the constants are central and can be relaxed.
- CSV rows are not validated by these DTOs; ingestion has its own validation ([ADR 0026](0026-ingestion-pipeline.md)).

## References

- [`src/main/java/com/educore/common/web/Paging.java`](../../src/main/java/com/educore/common/web/Paging.java)
- [`src/main/java/com/educore/common/web/SortWhitelist.java`](../../src/main/java/com/educore/common/web/SortWhitelist.java)
- [`src/main/java/com/educore/common/validation/InputPatterns.java`](../../src/main/java/com/educore/common/validation/InputPatterns.java)
- [`src/main/java/com/educore/account/UpdateStudentRequest.java`](../../src/main/java/com/educore/account/UpdateStudentRequest.java)
- [`src/test/java/com/educore/common/web/SortWhitelistTest.java`](../../src/test/java/com/educore/common/web/SortWhitelistTest.java)
- [`src/test/java/com/educore/web/ValidationIT.java`](../../src/test/java/com/educore/web/ValidationIT.java)
