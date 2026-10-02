package com.educore.auth;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    List<LoginAttempt> findByUsernameHashOrderByAtDescIdDesc(String usernameHash, Pageable pageable);

    List<LoginAttempt> findByUsernameHashAndClientKeyOrderByAtDescIdDesc(String usernameHash, String clientKey,
                                                                          Pageable pageable);

    boolean existsByUsernameHashAndClientKeyAndSuccessTrueAndAtAfter(String usernameHash, String clientKey,
                                                                     Instant after);

    /** Removes the failed attempts of {@code usernameHash} (ADMIN unlock); successes stay (trusted networks). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM LoginAttempt a WHERE a.usernameHash = :usernameHash AND a.success = false")
    int deleteFailures(@Param("usernameHash") String usernameHash);
}
