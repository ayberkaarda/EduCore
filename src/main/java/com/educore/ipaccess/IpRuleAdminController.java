package com.educore.ipaccess;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** IP allocation rules: {@code /api/v1/admin/ip-rules} (ADMIN). */
@RestController
@RequestMapping("/api/v1/admin/ip-rules")
@PreAuthorize("hasRole('ADMIN')")
public class IpRuleAdminController {

    private final IpRuleService ipRuleService;

    public IpRuleAdminController(IpRuleService ipRuleService) {
        this.ipRuleService = ipRuleService;
    }

    @GetMapping
    public List<IpRuleResponse> list() {
        return ipRuleService.list();
    }

    @PostMapping
    public ResponseEntity<IpRuleResponse> create(@Valid @RequestBody IpRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ipRuleService.create(request));
    }

    @DeleteMapping("/{ipRuleId}")
    public ResponseEntity<Void> delete(@PathVariable long ipRuleId) {
        ipRuleService.delete(ipRuleId);
        return ResponseEntity.noContent().build();
    }
}
