package com.educore.account;

import com.educore.common.web.ApiProblemException;
import com.educore.common.web.PageResponse;
import com.educore.common.web.Paging;
import com.educore.entity.Account;
import com.educore.entity.Role;
import com.educore.ipaccess.IpAllocationPolicy;
import com.educore.repository.AccountRepository;
import com.educore.security.AuthenticatedUser;
import com.educore.security.audit.AuditService;
import com.educore.security.audit.SecurityEventType;
import com.educore.service.AccountCredentialService;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Account administration (ADMIN only). Guards:
 * <ul>
 *   <li>an ADMIN cannot change their own role or delete their own account;</li>
 *   <li>the last active ADMIN can be neither demoted nor deleted. Both checks run after
 *       {@link AccountRepository#lockActiveAdminIds()} has locked every active ADMIN row, so concurrent
 *       requests cannot remove the last ADMIN between check and update.</li>
 * </ul>
 * Every mutation loads its target with a row lock ({@code SELECT ... FOR UPDATE}); the account's
 * {@code @Version} rejects any write based on a stale read.
 * Every effective change writes one audit event (actor, target, IP, request id; details hold no PII).
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class AccountAdminService {

    static final String NOT_FOUND = "account/not-found";
    static final String SELF_ROLE_CHANGE = "account/self-role-change";
    static final String SELF_DELETE = "account/self-delete";
    static final String LAST_ADMIN = "account/last-admin";
    static final String STUDENT_NUMBER_TAKEN = "account/student-number-taken";
    static final String USERNAME_TAKEN = "account/username-taken";
    static final String IP_ADDRESS_TAKEN = "account/ip-address-taken";
    static final String IP_ADDRESS_INVALID = "account/ip-address-invalid";
    static final String IP_ADDRESS_NOT_ALLOCATABLE = "account/ip-address-not-allocatable";

    private static final int USERNAME_ATTEMPTS = 20;

    private final AccountRepository accountRepository;
    private final AccountCredentialService accountCredentialService;
    private final IpAllocationPolicy ipAllocationPolicy;
    private final AuditService auditService;
    private final SecureRandom random = new SecureRandom();

    public AccountAdminService(AccountRepository accountRepository, AccountCredentialService accountCredentialService,
                               IpAllocationPolicy ipAllocationPolicy, AuditService auditService) {
        this.accountRepository = accountRepository;
        this.accountCredentialService = accountCredentialService;
        this.ipAllocationPolicy = ipAllocationPolicy;
        this.auditService = auditService;
    }

    /** Every account (any role) matching {@code search}, active or soft-deleted, ordered by id. */
    @Transactional(readOnly = true)
    public PageResponse<AccountResponse> searchAccounts(String search, boolean deleted, int page, int size) {
        return PageResponse.of(accountRepository
                .searchByDeleted(search, deleted ? 1 : 0, Paging.of(page, size, Sort.by("id")))
                .map(AccountResponse::of));
    }

    /** {@code USER} accounts matching {@code search}, active or soft-deleted, ordered by first name. */
    @Transactional(readOnly = true)
    public PageResponse<AccountResponse> searchStudents(String search, boolean deleted, int page, int size,
                                                        String direction) {
        Sort sort = Sort.by(Paging.direction(direction), "firstName").and(Sort.by("id"));
        return PageResponse.of(accountRepository
                .searchAccountsByRoleAndDeleted(Role.USER, search, deleted ? 1 : 0, Paging.of(page, size, sort))
                .map(AccountResponse::of));
    }

    @Transactional
    public CreatedStudentResponse createStudent(CreateStudentRequest request) {
        String studentNumber = blankToNull(request.studentNumber());
        if (studentNumber != null && accountRepository.existsByStudentNumber(studentNumber)) {
            // Soft-deleted accounts keep their number, so it can never be reused.
            throw ApiProblemException.conflict(STUDENT_NUMBER_TAKEN,
                    "An account with this student number already exists (deleted accounts included).");
        }
        String username = blankToNull(request.username());
        if (username == null) {
            username = generateUsername(request.firstName());
        } else if (accountRepository.findByUsername(username.trim()).isPresent()) {
            throw ApiProblemException.conflict(USERNAME_TAKEN, "This username is already taken.");
        } else {
            username = username.trim();
        }
        String ipAddress = checkedIpAddress(request.ipAddress(), null);

        Account account = Account.builder()
                .username(username)
                .firstName(request.firstName().trim())
                .lastName(request.lastName())
                .studentNumber(studentNumber)
                .ipAddress(ipAddress)
                .role(Role.USER)
                .deleted(0)
                .build();
        String temporaryPassword = accountCredentialService.assignTemporaryPassword(account);
        Account saved = accountRepository.saveAndFlush(account);
        auditService.recordAction(SecurityEventType.ACCOUNT_CREATED, saved.getId(), Map.of("role", Role.USER.name()));
        return CreatedStudentResponse.of(saved, temporaryPassword);
    }

    @Transactional
    public AccountResponse updateAccount(long accountId, UpdateStudentRequest request) {
        Account account = findForUpdate(accountId);
        String studentNumber = blankToNull(request.studentNumber());
        if (studentNumber != null && !studentNumber.equals(account.getStudentNumber())
                && accountRepository.existsByStudentNumber(studentNumber)) {
            throw ApiProblemException.conflict(STUDENT_NUMBER_TAKEN,
                    "An account with this student number already exists (deleted accounts included).");
        }
        String ipAddress = checkedIpAddress(request.ipAddress(), accountId);

        List<String> changed = new ArrayList<>();
        if (!Objects.equals(account.getFirstName(), request.firstName())) {
            changed.add("firstName");
        }
        if (!Objects.equals(account.getLastName(), request.lastName())) {
            changed.add("lastName");
        }
        if (!Objects.equals(account.getStudentNumber(), studentNumber)) {
            changed.add("studentNumber");
        }
        if (!Objects.equals(account.getIpAddress(), ipAddress)) {
            changed.add("ipAddress");
        }
        account.setFirstName(request.firstName());
        account.setLastName(request.lastName());
        account.setStudentNumber(studentNumber);
        account.setIpAddress(ipAddress);
        Account saved = accountRepository.saveAndFlush(account);
        if (!changed.isEmpty()) {
            auditService.recordAction(SecurityEventType.ACCOUNT_UPDATED, accountId, Map.of("fields", List.copyOf(changed)));
        }
        return AccountResponse.of(saved);
    }

    @Transactional
    public AccountResponse changeRole(AuthenticatedUser actor, long accountId, Role role) {
        if (actor.id() == accountId) {
            throw ApiProblemException.conflict(SELF_ROLE_CHANGE, "You cannot change your own role.");
        }
        List<Long> activeAdmins = accountRepository.lockActiveAdminIds();
        Account account = findForUpdate(accountId);
        Role previous = account.getRole();
        if (previous == role) {
            return AccountResponse.of(account);
        }
        if (previous == Role.ADMIN) {
            requireAnotherActiveAdmin(activeAdmins, accountId);
        }
        account.setRole(role);
        Account saved = accountRepository.saveAndFlush(account);
        auditService.recordAction(SecurityEventType.ROLE_CHANGED, accountId,
                Map.of("from", String.valueOf(previous), "to", role.name()));
        return AccountResponse.of(saved);
    }

    /** Soft delete ({@code deleted = 1}); idempotent for an account that is already deleted. */
    @Transactional
    public void softDelete(AuthenticatedUser actor, long accountId) {
        if (actor.id() == accountId) {
            throw ApiProblemException.conflict(SELF_DELETE, "You cannot delete your own account.");
        }
        List<Long> activeAdmins = accountRepository.lockActiveAdminIds();
        Account account = findForUpdate(accountId);
        if (account.getDeleted() != null && account.getDeleted() != 0) {
            return;
        }
        if (account.getRole() == Role.ADMIN) {
            requireAnotherActiveAdmin(activeAdmins, accountId);
        }
        account.setDeleted(1);
        accountRepository.saveAndFlush(account);
        auditService.recordAction(SecurityEventType.ACCOUNT_DELETED, accountId, Map.of("soft", true));
    }

    private static void requireAnotherActiveAdmin(List<Long> activeAdmins, long accountId) {
        boolean anotherAdmin = activeAdmins.stream().anyMatch(id -> id != accountId);
        if (!anotherAdmin) {
            throw ApiProblemException.conflict(LAST_ADMIN, "The last active administrator cannot be removed.");
        }
    }

    /**
     * The target row, locked until commit: concurrent writers of the same account wait and every mutation
     * starts from the latest committed state. {@code Account.version} (optimistic lock) is the second line of
     * defence; a version conflict is answered with 409 {@code request/concurrent-modification}.
     */
    private Account findForUpdate(long accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> ApiProblemException.notFound(NOT_FOUND, "Account not found."));
    }

    /**
     * {@code null} for a blank value; otherwise the trimmed address after format, allocation and uniqueness
     * checks ({@code ownerId} is the account allowed to hold it already).
     */
    private String checkedIpAddress(String value, Long ownerId) {
        String ip = blankToNull(value);
        if (ip == null) {
            return null;
        }
        if (!ipAllocationPolicy.isValidFormat(ip)) {
            throw ApiProblemException.badRequest(IP_ADDRESS_INVALID, "Invalid IPv4 address format.");
        }
        if (!ipAllocationPolicy.isAllocatable(ip)) {
            throw ApiProblemException.badRequest(IP_ADDRESS_NOT_ALLOCATABLE,
                    "This IP address is not inside any defined IP rule.");
        }
        accountRepository.findByIpAddress(ip)
                .filter(holder -> !holder.getId().equals(ownerId))
                .ifPresent(holder -> {
                    throw ApiProblemException.conflict(IP_ADDRESS_TAKEN,
                            "This IP address is already assigned to another account.");
                });
        return ip;
    }

    private String generateUsername(String firstName) {
        String base = firstName.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (base.isEmpty()) {
            base = "student";
        }
        for (int attempt = 0; attempt < USERNAME_ATTEMPTS; attempt++) {
            String candidate = base + (100 + random.nextInt(900));
            if (accountRepository.findByUsername(candidate).isEmpty()) {
                return candidate;
            }
        }
        return base + (100_000 + random.nextInt(900_000));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
