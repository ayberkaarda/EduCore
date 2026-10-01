package com.educore.ingestion;

import com.educore.repository.JobLogRepository;
import com.educore.security.audit.AuditService;
import com.educore.security.audit.SecurityEventType;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** CSV import job logs (ADMIN only). Deleting logs writes a {@code JOB_LOGS_DELETED} event. */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class JobLogService {

    private final JobLogRepository jobLogRepository;
    private final AuditService auditService;

    public JobLogService(JobLogRepository jobLogRepository, AuditService auditService) {
        this.jobLogRepository = jobLogRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<JobLogResponse> list() {
        return jobLogRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")))
                .stream().map(JobLogResponse::of).toList();
    }

    /** Deletes the existing logs among {@code ids}; unknown ids are skipped. */
    @Transactional
    public void delete(List<Long> ids) {
        List<Long> existing = jobLogRepository.findAllById(ids).stream().map(log -> log.getId()).sorted().toList();
        if (existing.isEmpty()) {
            return;
        }
        jobLogRepository.deleteAllByIdInBatch(existing);
        auditService.recordAction(SecurityEventType.JOB_LOGS_DELETED, null, Map.of("ids", existing));
    }
}
