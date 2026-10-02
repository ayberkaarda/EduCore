package com.educore.ingestion;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobLogEntryRepository extends JpaRepository<JobLogEntry, Long> {

    Page<JobLogEntry> findByJobLogId(Long jobLogId, Pageable pageable);

    List<JobLogEntry> findTop1000ByJobLogIdOrderByIdAsc(Long jobLogId);
}
