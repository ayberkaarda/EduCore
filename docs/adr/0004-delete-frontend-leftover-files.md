# 0004. Delete leftover frontend files

- Status: Accepted
- Date: 2026-09-25
- Original decision: D-04

## Context

The frontend directory contained `frontend/.neon` and `frontend/skills-lock.json`, which are not used by the
build, the tests or the runtime. Files of unknown purpose in the repository make secret scanning and reviews
harder. Their contents were read before the decision; they hold no connection details.

## Decision

Both files are deleted as leftovers.

## Consequences

Positive:

- Less unexplained content in the repository.

Negative:

- None known. Revert: restore the files from version control.

## References

- [`frontend/.gitignore`](../../frontend/.gitignore)
- [`.gitleaks.toml`](../../.gitleaks.toml)
- [`docs/DECISIONS_TAKEN.md`](../DECISIONS_TAKEN.md) (D-04)
