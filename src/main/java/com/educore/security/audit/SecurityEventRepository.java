package com.educore.security.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SecurityEventRepository extends JpaRepository<SecurityEvent, Long> {

    /** Events the account caused or was the target of, in the order of {@code pageable} (data export). */
    @Query("SELECT e FROM SecurityEvent e WHERE e.actorAccountId = :accountId OR e.targetAccountId = :accountId")
    List<SecurityEvent> findInvolving(@Param("accountId") Long accountId, Pageable pageable);
}
