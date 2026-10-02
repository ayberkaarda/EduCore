package com.educore.ipaccess;

import com.educore.common.web.PageResponse;
import com.educore.common.web.Paging;
import com.educore.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Request-level IP deny rules: {@code /api/v1/admin/ip-rules} (ADMIN), paginated CRUD. */
@RestController
@RequestMapping("/api/v1/admin/ip-rules")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class IpDenyRuleAdminController {

    private final IpDenyRuleService service;
    private final ClientIpResolver clientIpResolver;

    public IpDenyRuleAdminController(IpDenyRuleService service, ClientIpResolver clientIpResolver) {
        this.service = service;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping
    public PageResponse<IpDenyRuleResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(Paging.MAX_SIZE) int size) {
        return service.page(page, size);
    }

    @GetMapping("/{ipRuleId}")
    public IpDenyRuleResponse get(@PathVariable @Positive long ipRuleId) {
        return service.get(ipRuleId);
    }

    @PostMapping
    public ResponseEntity<IpDenyRuleResponse> create(@Valid @RequestBody IpDenyRuleRequest request,
                                                     HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request, clientIpResolver.resolve(http)));
    }

    @PutMapping("/{ipRuleId}")
    public IpDenyRuleResponse update(@PathVariable @Positive long ipRuleId,
                                     @Valid @RequestBody IpDenyRuleRequest request, HttpServletRequest http) {
        return service.update(ipRuleId, request, clientIpResolver.resolve(http));
    }

    @DeleteMapping("/{ipRuleId}")
    public ResponseEntity<Void> delete(@PathVariable @Positive long ipRuleId) {
        service.delete(ipRuleId);
        return ResponseEntity.noContent().build();
    }
}
