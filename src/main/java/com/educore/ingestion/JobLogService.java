package com.educore.ingestion;

import com.educore.common.query.LikePatterns;
import com.educore.common.web.ApiProblemException;
import com.educore.common.web.FieldViolation;
import com.educore.common.web.PageResponse;
import com.educore.common.web.Paging;
import com.educore.common.web.Problems;
import com.educore.common.web.SortWhitelist;
import com.educore.entity.JobLog;
import com.educore.entity.JobLogStatus;
import com.educore.repository.JobLogRepository;
import com.educore.security.audit.AuditService;
import com.educore.security.audit.SecurityEventType;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** CSV import job logs and their entries (ADMIN only). Deleting logs writes a {@code JOB_LOGS_DELETED} event. */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class JobLogService {

    static final String NOT_FOUND = "job-log/not-found";

    /** Sort keys of {@code GET /api/v1/admin/job-logs}; ties are broken by id (newest first). */
    static final SortWhitelist SORT = SortWhitelist.of("createdAt", Sort.by(Sort.Direction.DESC, "id"))
            .allow("createdAt", "createdAt")
            .allow("startedAt", "startedAt")
            .allow("fileName", "fileName")
            .allow("status", "status");

    private final JobLogRepository jobLogRepository;
    private final JobLogEntryRepository entryRepository;
    private final AuditService auditService;

    public JobLogService(JobLogRepository jobLogRepository, JobLogEntryRepository entryRepository,
                         AuditService auditService) {
        this.jobLogRepository = jobLogRepository;
        this.entryRepository = entryRepository;
        this.auditService = auditService;
    }

    /**
     * One page of job logs. Filters are optional and combined: {@code status}; {@code from}/{@code to}
     * (inclusive bounds on the start time); {@code file} (case-insensitive substring of the file name).
     */
    @Transactional(readOnly = true)
    public PageResponse<JobLogResponse> search(JobLogStatus status, Instant from, Instant to, String file, int page,
                                               int size, String sort, String direction) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRange();
        }
        Specification<JobLog> filter = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (from != null) {
            filter = filter.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("startedAt"), from));
        }
        if (to != null) {
            filter = filter.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("startedAt"), to));
        }
        if (file != null && !file.isBlank()) {
            String pattern = LikePatterns.contains(file.toLowerCase(Locale.ROOT));
            filter = filter.and((root, query, cb) ->
                    cb.like(cb.lower(root.get("fileName")), pattern, LikePatterns.ESCAPE));
        }
        Sort order = SORT.resolve(sort, direction == null || direction.isBlank() ? "desc" : direction);
        return PageResponse.of(jobLogRepository.findAll(filter, Paging.of(page, size, order)).map(JobLogResponse::of));
    }

    /** The entries of one job log in row order (file-level entries, which have no row, last). */
    @Transactional(readOnly = true)
    public PageResponse<JobLogEntryResponse> entries(long jobLogId, int page, int size) {
        if (!jobLogRepository.existsById(jobLogId)) {
            throw ApiProblemException.notFound(NOT_FOUND, "Job log not found.");
        }
        Sort rowOrder = Sort.by(Sort.Order.asc("rowNumber"), Sort.Order.asc("id"));
        return PageResponse.of(entryRepository.findByJobLogId(jobLogId, Paging.of(page, size, rowOrder))
                .map(JobLogEntryResponse::of));
    }

    /** Deletes the existing logs among {@code ids} (their entries go with them); unknown ids are skipped. */
    @Transactional
    public void delete(List<Long> ids) {
        List<Long> existing = jobLogRepository.findAllById(ids).stream().map(JobLog::getId).sorted().toList();
        if (existing.isEmpty()) {
            return;
        }
        jobLogRepository.deleteAllByIdInBatch(existing);
        auditService.recordAction(SecurityEventType.JOB_LOGS_DELETED, null, Map.of("ids", existing));
    }

    /** 400 {@code request/invalid} with {@code errors: [{field: from, code: range}]}. */
    static final class InvalidRange extends ApiProblemException {
        InvalidRange() {
            super(HttpStatus.BAD_REQUEST, Problems.INVALID_REQUEST, "The request is invalid.", null,
                    Map.of(Problems.ERRORS, List.of(new FieldViolation("from", "range"))));
        }
    }
}
