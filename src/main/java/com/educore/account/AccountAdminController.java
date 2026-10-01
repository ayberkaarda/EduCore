package com.educore.account;

import com.educore.common.web.PageResponse;
import com.educore.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Account administration: {@code /api/v1/admin/accounts} (ADMIN). */
@RestController
@RequestMapping("/api/v1/admin/accounts")
@PreAuthorize("hasRole('ADMIN')")
public class AccountAdminController {

    private final AccountAdminService accountAdminService;

    public AccountAdminController(AccountAdminService accountAdminService) {
        this.accountAdminService = accountAdminService;
    }

    @GetMapping
    public PageResponse<AccountResponse> searchAccounts(@RequestParam(defaultValue = "") String search,
                                                        @RequestParam(defaultValue = "false") boolean deleted,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "10") int size) {
        return accountAdminService.searchAccounts(search, deleted, page, size);
    }

    @GetMapping("/students")
    public PageResponse<AccountResponse> searchStudents(@RequestParam(defaultValue = "") String search,
                                                        @RequestParam(defaultValue = "false") boolean deleted,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "10") int size,
                                                        @RequestParam(defaultValue = "asc") String direction) {
        return accountAdminService.searchStudents(search, deleted, page, size, direction);
    }

    @PostMapping("/students")
    public ResponseEntity<CreatedStudentResponse> createStudent(@Valid @RequestBody CreateStudentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountAdminService.createStudent(request));
    }

    @PutMapping("/{accountId}")
    public AccountResponse updateAccount(@PathVariable long accountId,
                                         @Valid @RequestBody UpdateStudentRequest request) {
        return accountAdminService.updateAccount(accountId, request);
    }

    @DeleteMapping("/{accountId}")
    public ResponseEntity<Void> deleteAccount(@AuthenticationPrincipal AuthenticatedUser actor,
                                              @PathVariable long accountId) {
        accountAdminService.softDelete(actor, accountId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{accountId}/role")
    public AccountResponse changeRole(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long accountId,
                                      @Valid @RequestBody ChangeRoleRequest request) {
        return accountAdminService.changeRole(actor, accountId, request.role());
    }
}
