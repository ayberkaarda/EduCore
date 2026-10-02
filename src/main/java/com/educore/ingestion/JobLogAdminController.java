package com.educore.ingestion;

import com.educore.common.validation.InputPatterns;
import com.educore.common.web.PageResponse;
import com.educore.common.web.Paging;
import com.educore.entity.JobLogStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * CSV import job logs: {@code /api/v1/admin/job-logs} (ADMIN).
 * <ul>
 *   <li>{@code GET ?status=&from=&to=&file=&page=&size=&sort=&direction=}: one page, newest first by default.
 *       {@code status} SUCCEEDED|PARTIAL|FAILED; {@code from}/{@code to} ISO-8601 date-times with offset
 *       (start time, inclusive); {@code file} substring of the file name (at most 100 characters);
 *       {@code sort} createdAt|startedAt|fileName|status; {@code direction} asc|desc (default desc);
 *       {@code page >= 0}, {@code 1 <= size <= 100}.</li>
 *   <li>{@code GET /{jobLogId}/entries?page=&size=}: row-level entries in line order.</li>
 *   <li>{@code DELETE ?ids=1,2,3}: deletes logs and their entries.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/job-logs")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class JobLogAdminController {

    static final int MAX_IDS_PER_DELETE = 500;

    private final JobLogService jobLogService;

    public JobLogAdminController(JobLogService jobLogService) {
        this.jobLogService = jobLogService;
    }

    @GetMapping
    public PageResponse<JobLogResponse> list(
            @RequestParam(required = false) JobLogStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) @Size(max = 100) @Pattern(regexp = InputPatterns.SINGLE_LINE_TEXT) String file,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(Paging.MAX_SIZE) int size,
            @RequestParam(required = false) @Size(max = 32) String sort,
            @RequestParam(required = false) @Size(max = 4) String direction) {
        return jobLogService.search(status, from == null ? null : from.toInstant(), to == null ? null : to.toInstant(),
                file, page, size, sort, direction);
    }

    @GetMapping("/{jobLogId}/entries")
    public PageResponse<JobLogEntryResponse> entries(
            @PathVariable @Positive long jobLogId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(Paging.MAX_SIZE) int size) {
        return jobLogService.entries(jobLogId, page, size);
    }

    /** {@code ?ids=1,2,3}: 1 to {@value #MAX_IDS_PER_DELETE} positive ids. */
    @DeleteMapping
    public ResponseEntity<Void> delete(
            @RequestParam @Size(min = 1, max = MAX_IDS_PER_DELETE) List<@NotNull @Positive Long> ids) {
        jobLogService.delete(ids);
        return ResponseEntity.noContent().build();
    }
}
