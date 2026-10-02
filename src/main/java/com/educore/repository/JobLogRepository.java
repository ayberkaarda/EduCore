package com.educore.repository;

import com.educore.entity.JobLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface JobLogRepository extends JpaRepository<JobLog, Long>, JpaSpecificationExecutor<JobLog> {

    /** Open runs whose owner stopped renewing them (or that predate leases). */
    @Query("SELECT l FROM JobLog l WHERE l.status IS NULL AND (l.leaseUntil IS NULL OR l.leaseUntil < :now) "
            + "ORDER BY l.id")
    List<JobLog> findExpiredOpenRuns(@Param("now") Instant now);

    /** Snapshot names of open runs whose lease is still valid (any owner). */
    @Query("SELECT l.snapshotName FROM JobLog l WHERE l.status IS NULL AND l.leaseUntil >= :now "
            + "AND l.snapshotName IS NOT NULL")
    List<String> findLiveSnapshotNames(@Param("now") Instant now);
}
