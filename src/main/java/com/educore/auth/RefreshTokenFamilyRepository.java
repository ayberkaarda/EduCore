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

public interface RefreshTokenFamilyRepository extends JpaRepository<RefreshTokenFamily, UUID> {

    /**
     * Row-locks the family. Every rotation, reuse revocation and logout of the family takes this lock first,
     * so they are serialised and none of them can miss a token inserted by another.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM RefreshTokenFamily f WHERE f.id = :id")
    Optional<RefreshTokenFamily> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Marks every live family of the account revoked. The UPDATE row-locks each family, so it waits for
     * rotations in progress and blocks later ones until the surrounding transaction ends.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshTokenFamily f SET f.revokedAt = :at WHERE f.accountId = :accountId AND f.revokedAt IS NULL")
    int revokeAllForAccount(@Param("accountId") Long accountId, @Param("at") Instant at);
}
