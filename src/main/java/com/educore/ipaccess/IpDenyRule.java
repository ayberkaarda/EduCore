package com.educore.ipaccess;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A request-level deny rule ({@code ip_deny_rule}, D-09): requests whose resolved client IP lies inside
 * {@code [startIp, endIp]} are answered 403 {@code ipaccess/denied} by {@link IpAccessControlFilter} until
 * {@code expiresAt} ({@code null} = permanent). {@code value} is the canonical text of the range in
 * {@code kind} notation; the numeric bounds are always computed by the server.
 */
@Entity
@Table(name = "ip_deny_rule")
@Getter
@Setter
@NoArgsConstructor
public class IpDenyRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private IpRangeKind kind;

    @Column(nullable = false, length = 31)
    private String value;

    @Column(name = "start_ip", nullable = false)
    private long startIp;

    @Column(name = "end_ip", nullable = false)
    private long endIp;

    @Column(length = 200)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private IpDenyRuleSource source;

    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Account id of the ADMIN who created the rule; {@code null} for automatic rules. */
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    void applyRange(IpRangeKind kind, Ipv4Range range) {
        this.kind = kind;
        this.value = IpRanges.format(kind, range);
        this.startIp = range.start().toLong();
        this.endIp = range.end().toLong();
    }
}
