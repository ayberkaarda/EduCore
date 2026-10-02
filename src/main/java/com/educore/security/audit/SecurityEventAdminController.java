package com.educore.security.audit;

import com.educore.common.web.PageResponse;
import com.educore.common.web.Paging;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only audit trail: {@code GET /api/v1/admin/security-events} (ADMIN), newest first (fixed order, no
 * sort parameter); {@code page >= 0}, {@code 1 <= size <= 100}.
 */
@RestController
@RequestMapping("/api/v1/admin/security-events")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class SecurityEventAdminController {

    private final AuditService auditService;

    public SecurityEventAdminController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public PageResponse<SecurityEventResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(Paging.MAX_SIZE) int size) {
        return auditService.page(page, size);
    }
}
