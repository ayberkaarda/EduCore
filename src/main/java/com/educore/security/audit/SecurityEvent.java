package com.educore.security.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** One row of the security audit trail ({@code security_event}). Written once, never updated. */
@Entity
@Table(name = "security_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SecurityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private SecurityEventType type;

    private Long actorAccountId;

    private Long targetAccountId;

    @Column(length = 45)
    private String ip;

    @Column(length = 64)
    private String requestId;

    @Column(nullable = false)
    private Instant at;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> details;

    public SecurityEvent(SecurityEventType type, Long actorAccountId, Long targetAccountId, String ip,
                         String requestId, Instant at, Map<String, Object> details) {
        this.type = type;
        this.actorAccountId = actorAccountId;
        this.targetAccountId = targetAccountId;
        this.ip = ip;
        this.requestId = requestId;
        this.at = at;
        this.details = details;
    }
}
