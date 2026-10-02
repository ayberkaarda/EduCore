# 0021. Escaped LIKE patterns and a bytecode rule against runtime-built queries

- Status: Accepted
- Date: 2026-10-02
- Original decisions: D-NEW-15, D-NEW-16

## Context

Search endpoints use `LIKE`. User input containing `%` or `_` changes the meaning of a pattern, and a
backslash escape behaves differently depending on PostgreSQL's `standard_conforming_strings`. Separately, the
rule "no concatenated query strings" needs an automated check; a source-level regular expression cannot tell
constant concatenation from concatenation of runtime values.

## Decision

- LIKE searches bind `LikePatterns.contains(input)` and declare `ESCAPE '!'`; `!`, `%` and `_` in the input are
  escaped. `!` needs no escaping in Java, JPQL or PostgreSQL string literals.
- `ArchitectureTest` contains a bytecode rule: a method that calls a query sink
  (`EntityManager.createQuery/createNativeQuery`, Hibernate `createQuery/createNativeQuery/createSelectionQuery/createMutationQuery`,
  JDBC/JdbcTemplate query methods) must not build strings at runtime (`StringConcatFactory` invokedynamic,
  `StringBuilder`/`StringBuffer.append`, `String.concat/format/formatted/join`). Because javac folds constant
  concatenation, remaining string building in bytecode is runtime-dependent.

## Consequences

Positive:

- Search input is always literal; no wildcard injection.
- Query construction from runtime strings fails the build.

Negative:

- The bytecode rule does not see a query string built in another method and passed in; residual blind spots
  are listed in `ArchitectureTest`.

## References

- [`src/main/java/com/educore/common/query/LikePatterns.java`](../../src/main/java/com/educore/common/query/LikePatterns.java)
- [`src/test/java/com/educore/common/query/LikeEscapeTest.java`](../../src/test/java/com/educore/common/query/LikeEscapeTest.java)
- [`src/test/java/com/educore/architecture/ArchitectureTest.java`](../../src/test/java/com/educore/architecture/ArchitectureTest.java)
