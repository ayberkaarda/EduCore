# 0002. Remove the SOAP endpoint `/ws/**`

- Status: Accepted
- Date: 2026-09-25
- Original decision: D-02

## Context

The application exposed a SOAP web service under `/ws/**` (`SoapWebServiceConfig`, `StudentSoapEndpoint`,
`students.xsd`, JAXB-generated classes in `com.educore.soap`). The path was listed as `permitAll`, and the
endpoint created accounts with a plaintext default password, which conflicts with the password rules of the
authentication hardening (no default or plaintext passwords). No external consumer of the endpoint was
found in the repository. The alternative was to keep it behind WS-Security UsernameToken over TLS, ADMIN-only,
with hashed passwords and DTO mapping.

## Decision

The SOAP endpoint is removed entirely: `config/SoapWebServiceConfig.java`, `endpoint/StudentSoapEndpoint.java`,
`src/main/resources/students.xsd`, the `jaxb2-maven-plugin` and its JaCoCo exclusion, and the
`spring-boot-starter-web-services` and `wsdl4j` dependencies. `/ws/**` is no longer in the anonymous list of
`SecurityConfig`; like every unlisted path it now requires authentication. The removal was carried out in
phase P2 instead of P3 because of the plaintext password.

## Consequences

Positive:

- One anonymous entry point and one account-creation path less; account creation goes only through the
  REST admin API with generated temporary passwords.
- Smaller build (no code generation step) and fewer dependencies.

Negative:

- Any undiscovered SOAP client stops working and must move to the REST API (`docs/api/ROUTES.md`).

Revert: restore the removed files and build dependencies from version control.

## References

- [`src/main/java/com/educore/security/SecurityConfig.java`](../../src/main/java/com/educore/security/SecurityConfig.java)
- [`pom.xml`](../../pom.xml)
- [`docs/api/ROUTES.md`](../api/ROUTES.md)
- [`docs/DECISIONS_TAKEN.md`](../DECISIONS_TAKEN.md) (D-02)
