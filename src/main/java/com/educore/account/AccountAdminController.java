package com.educore.account;

import com.educore.common.validation.InputPatterns;
import com.educore.common.web.PageResponse;
import com.educore.common.web.Paging;
import com.educore.common.web.SortWhitelist;
import com.educore.lifecycle.AccountLifecycleService;
import com.educore.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

/**
 * Account administration: {@code /api/v1/admin/accounts} (ADMIN). The two listings accept
 * {@code page >= 0}, {@code 1 <= size <= 100}, a {@code sort} key from their {@link SortWhitelist} and
 * {@code direction=asc|desc}; anything else is a 400 problem.
 */
@RestController
@RequestMapping("/api/v1/admin/accounts")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AccountAdminController {

    /** {@code GET /admin/accounts}: default {@code id}. */
    static final SortWhitelist ACCOUNT_SORT = SortWhitelist.of("id", Sort.by("id"))
            .allow("id", "id")
            .allow("username", "username")
            .allow("firstName", "firstName")
            .allow("lastName", "lastName")
            .allow("studentNumber", "studentNumber");

    /** {@code GET /admin/accounts/students}: default {@code firstName}, ties broken by id. */
    static final SortWhitelist STUDENT_SORT = SortWhitelist.of("firstName", Sort.by("id"))
            .allow("firstName", "firstName")
            .allow("lastName", "lastName")
            .allow("studentNumber", "studentNumber")
            .allow("id", "id");

    private final AccountAdminService accountAdminService;
    private final AccountLifecycleService lifecycleService;

    public AccountAdminController(AccountAdminService accountAdminService, AccountLifecycleService lifecycleService) {
        this.accountAdminService = accountAdminService;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping
    public PageResponse<AccountResponse> searchAccounts(
            @RequestParam(defaultValue = "") @Size(max = 100) @Pattern(regexp = InputPatterns.SINGLE_LINE_TEXT)
            String search,
            @RequestParam(defaultValue = "false") boolean deleted,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(Paging.MAX_SIZE) int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        Pageable pageable = Paging.of(page, size, ACCOUNT_SORT.resolve(sort, direction));
        return accountAdminService.searchAccounts(search, deleted, pageable);
    }

    @GetMapping("/students")
    public PageResponse<AccountResponse> searchStudents(
            @RequestParam(defaultValue = "") @Size(max = 100) @Pattern(regexp = InputPatterns.SINGLE_LINE_TEXT)
            String search,
            @RequestParam(defaultValue = "false") boolean deleted,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(Paging.MAX_SIZE) int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        Pageable pageable = Paging.of(page, size, STUDENT_SORT.resolve(sort, direction));
        return accountAdminService.searchStudents(search, deleted, pageable);
    }

    @PostMapping("/students")
    public ResponseEntity<CreatedStudentResponse> createStudent(@Valid @RequestBody CreateStudentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountAdminService.createStudent(request));
    }

    @PutMapping("/{accountId}")
    public AccountResponse updateAccount(@PathVariable @Positive long accountId,
                                         @Valid @RequestBody UpdateStudentRequest request) {
        return accountAdminService.updateAccount(accountId, request);
    }

    /**
     * Soft delete: {@code DEACTIVATED}, restorable, every session of the account ended. {@code mode} accepts only
     * {@code soft} (the default); the hard delete is {@code POST /{accountId}/purge} with the confirmation in the
     * body, so {@code mode=hard} answers 400. Keeps the self and last-ADMIN guards.
     */
    @DeleteMapping("/{accountId}")
    public ResponseEntity<Void> deleteAccount(@AuthenticationPrincipal AuthenticatedUser actor,
                                              @PathVariable @Positive long accountId,
                                              @RequestParam(defaultValue = "soft") @Pattern(regexp = "soft")
                                              String mode) {
        accountAdminService.softDelete(actor, accountId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Hard delete (immediate purge, docs/ops/DATA_RETENTION.md): {@code confirm} in the JSON body must equal the
     * account's username (400 {@code account/confirmation-mismatch}); self and last-ADMIN guards apply.
     */
    @PostMapping("/{accountId}/purge")
    public ResponseEntity<Void> purgeAccount(@AuthenticationPrincipal AuthenticatedUser actor,
                                             @PathVariable @Positive long accountId,
                                             @Valid @RequestBody PurgeAccountRequest request) {
        lifecycleService.hardDelete(actor, accountId, request.confirm());
        return ResponseEntity.noContent().build();
    }

    /**
     * Clears the failed login attempts of the account (every (username, client) lock and the per-account delay end
     * at once); audited {@code ACCOUNT_LOGIN_UNLOCKED}.
     */
    @PostMapping("/{accountId}/unlock-login")
    public ResponseEntity<Void> unlockLogin(@PathVariable @Positive long accountId) {
        accountAdminService.unlockLogin(accountId);
        return ResponseEntity.noContent().build();
    }

    /** Reactivates a {@code DEACTIVATED} or {@code PENDING_DELETION} account (no-op for an active one). */
    @PostMapping("/{accountId}/restore")
    public AccountResponse restoreAccount(@PathVariable @Positive long accountId) {
        return lifecycleService.restore(accountId);
    }

    @PutMapping("/{accountId}/role")
    public AccountResponse changeRole(@AuthenticationPrincipal AuthenticatedUser actor,
                                      @PathVariable @Positive long accountId,
                                      @Valid @RequestBody ChangeRoleRequest request) {
        return accountAdminService.changeRole(actor, accountId, request.role());
    }
}
