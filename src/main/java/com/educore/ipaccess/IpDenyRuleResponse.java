package com.educore.ipaccess;

import java.time.Instant;

/** A deny rule as returned by the admin API; {@code startIp}/{@code endIp} are dotted quads. */
public record IpDenyRuleResponse(Long id, IpRangeKind kind, String value, String startIp, String endIp,
                                 String reason, IpDenyRuleSource source, Instant expiresAt, Long createdBy,
                                 Instant createdAt) {

    static IpDenyRuleResponse of(IpDenyRule rule) {
        return new IpDenyRuleResponse(rule.getId(), rule.getKind(), rule.getValue(),
                Ipv4.fromLong(rule.getStartIp()).toString(), Ipv4.fromLong(rule.getEndIp()).toString(),
                rule.getReason(), rule.getSource(), rule.getExpiresAt(), rule.getCreatedBy(), rule.getCreatedAt());
    }
}
