package com.educore.security.audit;

import com.educore.common.web.PageResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only audit trail: {@code GET /api/v1/admin/security-events} (ADMIN), newest first. */
@RestController
@RequestMapping("/api/v1/admin/security-events")
@PreAuthorize("hasRole('ADMIN')")
public class SecurityEventAdminController {

    private final AuditService auditService;

    public SecurityEventAdminController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public PageResponse<SecurityEventResponse> list(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return auditService.page(page, size);
    }
}
