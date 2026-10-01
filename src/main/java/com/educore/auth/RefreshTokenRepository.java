package com.educore.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * The family of a token, as a scalar: no entity is loaded, so the row is read fresh by
     * {@link #findByTokenHashForUpdate} once the family lock is held.
     */
    @Query("SELECT t.familyId FROM RefreshToken t WHERE t.tokenHash = :tokenHash")
    Optional<UUID> findFamilyIdByTokenHash(@Param("tokenHash") String tokenHash);

    /** Row-locks the token. Callers hold the family lock already ({@link RefreshTokenFamilyRepository}). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /** Revokes the family's active tokens. Callers hold the family lock, so no successor can be in flight. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :at WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("at") Instant at);

    /** Revokes the account's active tokens. Callers revoke (and thereby lock) the account's families first. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :at WHERE t.accountId = :accountId AND t.revokedAt IS NULL")
    int revokeAllForAccount(@Param("accountId") Long accountId, @Param("at") Instant at);
}
