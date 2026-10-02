package com.educore.ipaccess;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface IpDenyRuleRepository extends JpaRepository<IpDenyRule, Long> {

    /** Rules in force at {@code now}: permanent or not yet expired. */
    @Query("SELECT r FROM IpDenyRule r WHERE r.expiresAt IS NULL OR r.expiresAt > :now")
    List<IpDenyRule> findActive(@Param("now") Instant now);

    /** Deletes the rules that expired at or before {@code now}; returns how many were deleted. */
    @Modifying
    @Query("DELETE FROM IpDenyRule r WHERE r.expiresAt IS NOT NULL AND r.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}
