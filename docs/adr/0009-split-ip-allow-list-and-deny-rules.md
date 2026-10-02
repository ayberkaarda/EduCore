# 0009. Split the student IP allow-list from request-level IP deny rules

- Status: Accepted
- Date: 2026-09-25
- Original decision: D-09

## Context

The `IpBlock` entity (table `ip_block`) was named as if it blocked requests, but it only limited which
addresses an ADMIN could assign to a student account. No request-level IP blocking existed.

## Decision

- `IpBlock` is renamed to `IpAllocationRange`; `V12__ip_access.sql` renames the table to
  `ip_allocation_range` and keeps its data. Its admin routes move to `/api/v1/admin/ip-allocations`.
- A new table `ip_deny_rule` (kinds `STATIC`, `RANGE`, `CIDR`; source `MANUAL` or `AUTO`; optional expiry)
  backs `IpAccessControlFilter`, the first filter of the application security chain. Rules are cached
  (`IpDenyRuleCache`). `/api/v1/admin/ip-rules` now manages these deny rules.
- `IpAutoDenyService` writes one `AUTO` rule per address after repeated failed logins
  (`V13__ip_deny_auto_unique.sql` makes it unique per address).
- Audit events are separate: `IP_ALLOCATION_CHANGED` and `IP_RULE_CHANGED`. `IpAddressUtil` is replaced by the
  `Ipv4` and `Ipv4Range` value objects.

## Consequences

Positive:

- Names match behaviour; the allow-list and the deny list cannot be confused in the API, audit trail or UI.
- Real request-level blocking exists, without a database query per request.

Negative:

- A schema rename and a route change: clients of the old `/api/v1/admin/ip-rules` semantics must move to
  `/api/v1/admin/ip-allocations`.
- Deny rules are IPv4 only; native IPv6 clients are governed by `educore.ipaccess.ipv6-policy`.

## References

- [`src/main/resources/db/migration/V12__ip_access.sql`](../../src/main/resources/db/migration/V12__ip_access.sql)
- [`src/main/resources/db/migration/V13__ip_deny_auto_unique.sql`](../../src/main/resources/db/migration/V13__ip_deny_auto_unique.sql)
- [`src/main/java/com/educore/ipaccess/IpAllocationRange.java`](../../src/main/java/com/educore/ipaccess/IpAllocationRange.java)
- [`src/main/java/com/educore/ipaccess/IpAccessControlFilter.java`](../../src/main/java/com/educore/ipaccess/IpAccessControlFilter.java)
- [`src/main/java/com/educore/ipaccess/IpDenyRuleCache.java`](../../src/main/java/com/educore/ipaccess/IpDenyRuleCache.java)
- [`src/main/java/com/educore/ipaccess/IpAutoDenyService.java`](../../src/main/java/com/educore/ipaccess/IpAutoDenyService.java)
- [`docs/security/IP_ACCESS.md`](../security/IP_ACCESS.md)
- [`docs/api/ROUTES.md`](../api/ROUTES.md)
