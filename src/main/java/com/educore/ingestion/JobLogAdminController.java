package com.educore.ingestion;

import com.educore.common.web.ApiProblemException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** CSV import job logs: {@code /api/v1/admin/job-logs} (ADMIN). */
@RestController
@RequestMapping("/api/v1/admin/job-logs")
@PreAuthorize("hasRole('ADMIN')")
public class JobLogAdminController {

    static final int MAX_IDS_PER_DELETE = 500;

    private final JobLogService jobLogService;

    public JobLogAdminController(JobLogService jobLogService) {
        this.jobLogService = jobLogService;
    }

    @GetMapping
    public List<JobLogResponse> list() {
        return jobLogService.list();
    }

    /** {@code ?ids=1,2,3}. */
    @DeleteMapping
    public ResponseEntity<Void> delete(@RequestParam List<Long> ids) {
        if (ids.isEmpty() || ids.size() > MAX_IDS_PER_DELETE || ids.stream().anyMatch(id -> id == null)) {
            throw ApiProblemException.badRequest("request/invalid", "The request is invalid.");
        }
        jobLogService.delete(ids);
        return ResponseEntity.noContent().build();
    }
}
