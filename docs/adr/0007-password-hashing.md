# 0007. Password hashing with a delegating bcrypt encoder and upgrade on login

- Status: Accepted
- Date: 2026-09-25
- Original decision: D-07

## Context

Existing accounts store plain bcrypt hashes without an algorithm prefix (earlier versions and the dev seed).
The hashing algorithm and its cost must be changeable later without forcing every user to reset a password.

## Decision

`SecurityConfig.passwordEncoder()` returns a `DelegatingPasswordEncoder` whose default id is `bcrypt` with
`BCryptPasswordEncoder` strength 12. Hashes without a prefix are matched by the same bcrypt encoder
(`setDefaultPasswordEncoderForMatches`) and report `upgradeEncoding == true`, so `AuthService` re-hashes them
with the current default after the next successful login.

## Consequences

Positive:

- New hashes carry an algorithm id (`{bcrypt}`), so a future algorithm can be added without a migration.
- Legacy hashes are upgraded transparently.

Negative:

- Strength 12 costs noticeably more CPU per login than the bcrypt default of 10; login throttling
  (`educore.security.login.*`) limits the impact.
- Accounts that never sign in again keep their legacy hash.

## References

- [`src/main/java/com/educore/security/SecurityConfig.java`](../../src/main/java/com/educore/security/SecurityConfig.java)
- [`src/main/java/com/educore/auth/AuthService.java`](../../src/main/java/com/educore/auth/AuthService.java)
- [`src/test/java/com/educore/security/PasswordHashingTest.java`](../../src/test/java/com/educore/security/PasswordHashingTest.java)
