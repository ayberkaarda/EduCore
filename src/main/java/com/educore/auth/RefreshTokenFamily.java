package com.educore.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * A refresh token family: the chain of tokens created by one login. Its row is locked before any of the
 * family's tokens are rotated or revoked, and {@code revokedAt} marks the family as permanently unusable.
 */
@Entity
@Table(name = "refresh_token_family")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshTokenFamily implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false)
    private Long accountId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant revokedAt;

    @Transient
    private boolean fresh;

    RefreshTokenFamily(UUID id, Long accountId, Instant createdAt) {
        this.id = id;
        this.accountId = accountId;
        this.createdAt = createdAt;
        this.fresh = true;
    }

    boolean isRevoked() {
        return revokedAt != null;
    }

    void revoke(Instant at) {
        if (revokedAt == null) {
            revokedAt = at;
        }
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.fresh = false;
    }
}
