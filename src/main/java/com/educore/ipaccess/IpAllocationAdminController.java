package com.educore.ipaccess;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The student IP allow-list: {@code /api/v1/admin/ip-allocations} (ADMIN). Until P5 these routes were
 * {@code /api/v1/admin/ip-rules}, which now manages request-level deny rules ({@link IpDenyRuleAdminController}).
 */
@RestController
@RequestMapping("/api/v1/admin/ip-allocations")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class IpAllocationAdminController {

    private final IpAllocationService service;

    public IpAllocationAdminController(IpAllocationService service) {
        this.service = service;
    }

    @GetMapping
    public List<IpAllocationResponse> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<IpAllocationResponse> create(@Valid @RequestBody IpAllocationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @DeleteMapping("/{ipAllocationId}")
    public ResponseEntity<Void> delete(@PathVariable @Positive long ipAllocationId) {
        service.delete(ipAllocationId);
        return ResponseEntity.noContent().build();
    }
}
