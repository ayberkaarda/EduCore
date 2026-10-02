package com.educore.lifecycle;

import com.educore.account.AccountResponse;
import com.educore.auth.AttemptLocks;
import com.educore.auth.AuthService;
import com.educore.auth.ClientInfo;
import com.educore.auth.CurrentPasswordCheck;
import com.educore.auth.RefreshTokenService;
import com.educore.common.web.ApiProblemException;
import com.educore.entity.Account;
import com.educore.entity.AccountStatus;
import com.educore.entity.Role;
import com.educore.repository.AccountRepository;
import com.educore.security.ActiveAccount;
import com.educore.security.AuthenticatedUser;
import com.educore.security.audit.AuditService;
import com.educore.security.audit.SecurityEventType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Account deletion and restore (P7, S21; docs/ops/DATA_RETENTION.md).
 * <ul>
 *   <li>{@link #requestDeletion}: the owner, with the current password, moves an active account to
 *       {@code PENDING_DELETION} for {@code educore.lifecycle.grace-days} (30) and every refresh token family
 *       is revoked and the session epoch incremented (earlier access tokens end at once). During the grace period
 *       the owner may sign in with the restore-only scope.</li>
 *   <li>{@link #restoreOwn}: the owner, with the current password, cancels the deletion inside the grace period
 *       and receives a new session (every earlier session ends).</li>
 *   <li>{@link #hardDelete}: an ADMIN purges an account at once ({@code POST .../purge}, confirmed with its
 *       username in the body); self and last-ADMIN guards apply.</li>
 *   <li>{@link #restore}: an ADMIN reactivates a {@code DEACTIVATED} or {@code PENDING_DELETION} account; every
 *       earlier session ends.</li>
 * </ul>
 * Lock order: every active ADMIN row ({@link AccountRepository#lockActiveAdminIds()}), then the per-username
 * attempt lock and the account row ({@link CurrentPasswordCheck}), then refresh token families.
 */
@Service
public class AccountLifecycleService {

    static final String NOT_FOUND = "account/not-found";
    static final String SELF_DELETE = "account/self-delete";
    static final String LAST_ADMIN = "account/last-admin";
    static final String CONFIRMATION_MISMATCH = "account/confirmation-mismatch";
    static final String NOT_RESTORABLE = "account/not-restorable";

    private final AccountRepository accounts;
    private final CurrentPasswordCheck currentPasswordCheck;
    private final RefreshTokenService refreshTokens;
    private final AccountPurger purger;
    private final AuthService authService;
    private final AuditService audit;
    private final Clock clock;
    private final Duration grace;
    private final TransactionTemplate transaction;

    public AccountLifecycleService(AccountRepository accounts, CurrentPasswordCheck currentPasswordCheck,
                                   RefreshTokenService refreshTokens, AccountPurger purger, AuthService authService,
                                   AuditService audit, Clock clock, LifecycleProperties properties,
                                   PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.currentPasswordCheck = currentPasswordCheck;
        this.refreshTokens = refreshTokens;
        this.purger = purger;
        this.authService = authService;
        this.audit = audit;
        this.clock = clock;
        this.grace = Duration.ofDays(properties.graceDays());
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** What the deletion transaction produced: the answer, or the problem to throw once it has committed. */
    private record Outcome(DeletionScheduledResponse response, ApiProblemException problem) {
    }

    /**
     * {@code DELETE /api/v1/me}. A wrong current password answers 400 {@code auth/invalid-current-password} and
     * counts towards the login lockout (423 {@code auth/account-locked} once locked); the last active ADMIN
     * answers 409 {@code account/last-admin}. Failures are committed (attempt and event) before the problem is
     * thrown.
     */
    public DeletionScheduledResponse requestDeletion(AuthenticatedUser user, DeleteAccountRequest request,
                                                     ClientInfo client) {
        String username = accounts.findById(user.id()).filter(ActiveAccount::isActive).map(Account::getUsername)
                .orElseThrow(() -> ApiProblemException.notFound(NOT_FOUND, "Account not found."));
        Outcome outcome;
        try {
            outcome = transaction.execute(status -> requestDeletionLocked(user.id(), username, request, client));
        } catch (AttemptLocks.Busy e) {
            throw CurrentPasswordCheck.busyProblem();
        }
        if (outcome.problem() != null) {
            throw outcome.problem();
        }
        return outcome.response();
    }

    private Outcome requestDeletionLocked(long accountId, String username, DeleteAccountRequest request,
                                          ClientInfo client) {
        List<Long> activeAdmins = accounts.lockActiveAdminIds();
        CurrentPasswordCheck.Result check = currentPasswordCheck.verify(accountId, username,
                request.currentPassword(), client, "deletion_bad_current");
        if (!check.verified()) {
            return new Outcome(null, check.problem());
        }
        if (check.account().getRole() == Role.ADMIN && isLastActiveAdmin(activeAdmins, accountId)) {
            return new Outcome(null, lastAdmin());
        }
        currentPasswordCheck.recordSuccess(username, client);
        Instant now = clock.instant();
        Instant deleteAfter = now.plus(grace);
        if (accounts.markPendingDeletion(accountId, now, deleteAfter, AccountStatus.PENDING_DELETION,
                AccountStatus.ACTIVE) != 1) {
            // The account row is locked and was active when verified; never report success for a missed update.
            throw new IllegalStateException("Account status changed while locked");
        }
        int revoked = refreshTokens.revokeAll(accountId);
        audit.record(SecurityEventType.ACCOUNT_DELETION_REQUESTED, accountId, accountId, client.ip(),
                Map.of("graceDays", grace.toDays(), "revokedRefreshTokens", revoked));
        return new Outcome(new DeletionScheduledResponse(AccountStatus.PENDING_DELETION, deleteAfter), null);
    }

    /** What the restore transaction produced: the new session, or the problem to throw once it has committed. */
    private record RestoreOutcome(AuthService.Session session, ApiProblemException problem) {
    }

    /**
     * {@code POST /api/v1/me/restore} {@code {currentPassword}}: fresh authentication is required, so a bearer token
     * stolen before the deletion request cannot restore the account (R-16, AC-10). A wrong password answers 400
     * {@code auth/invalid-current-password} and counts towards the lockout like the deletion request.
     * <p>
     * {@code PENDING_DELETION} inside the grace period becomes {@code ACTIVE} ({@code ACCOUNT_RESTORED}); every
     * refresh token family is revoked and the session epoch incremented, so every earlier session (refresh cookie
     * or access token) ends, and the answer is a new session as after a login. For an account that is already
     * active nothing changes (no event, nothing revoked) and the answer is a new session as well.
     */
    public AuthService.Session restoreOwn(AuthenticatedUser user, RestoreAccountRequest request, ClientInfo client) {
        String username = accounts.findById(user.id())
                .filter(account -> ActiveAccount.mayAuthenticate(account, clock.instant()))
                .map(Account::getUsername)
                .orElseThrow(() -> ApiProblemException.notFound(NOT_FOUND, "Account not found."));
        RestoreOutcome outcome;
        try {
            outcome = transaction.execute(status -> restoreOwnLocked(user.id(), username, request, client));
        } catch (AttemptLocks.Busy e) {
            throw CurrentPasswordCheck.busyProblem();
        }
        if (outcome.problem() != null) {
            throw outcome.problem();
        }
        return outcome.session();
    }

    private RestoreOutcome restoreOwnLocked(long accountId, String username, RestoreAccountRequest request,
                                            ClientInfo client) {
        Instant now = clock.instant();
        CurrentPasswordCheck.Result check = currentPasswordCheck.verify(accountId, username,
                request.currentPassword(), client, "restore_bad_current",
                account -> ActiveAccount.mayAuthenticate(account, now));
        if (!check.verified()) {
            return new RestoreOutcome(null, check.problem());
        }
        currentPasswordCheck.recordSuccess(username, client);
        Account account = check.account();
        if (!ActiveAccount.isActive(account)) {
            account = reactivate(account);
            int revoked = refreshTokens.revokeAll(accountId);
            audit.record(SecurityEventType.ACCOUNT_RESTORED, accountId, accountId, client.ip(),
                    Map.of("from", AccountStatus.PENDING_DELETION.name(), "revokedRefreshTokens", revoked));
        }
        return new RestoreOutcome(authService.issueSession(account, client), null);
    }

    /**
     * {@code POST /api/v1/admin/accounts/{id}/purge} {@code {confirm}}: purges the account now. The confirmation
     * travels in the JSON body, never in the URL, so the username of the purged person cannot persist in access
     * logs (R-22). 409 {@code account/self-delete} for the caller's own account, 409 {@code account/last-admin} for
     * the last active ADMIN, 400 {@code account/confirmation-mismatch} unless {@code confirm} equals the username.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void hardDelete(AuthenticatedUser actor, long accountId, String confirm) {
        if (actor.id() == accountId) {
            throw ApiProblemException.conflict(SELF_DELETE, "You cannot delete your own account.");
        }
        List<Long> activeAdmins = accounts.lockActiveAdminIds();
        Account account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> ApiProblemException.notFound(NOT_FOUND, "Account not found."));
        if (confirm == null || !confirm.equals(account.getUsername())) {
            throw ApiProblemException.badRequest(CONFIRMATION_MISMATCH,
                    "The confirm field must equal the account's username.");
        }
        if (ActiveAccount.isActive(account) && account.getRole() == Role.ADMIN
                && isLastActiveAdmin(activeAdmins, accountId)) {
            throw lastAdmin();
        }
        purger.purge(accountId, AccountPurger.Trigger.ADMIN_HARD_DELETE);
    }

    /**
     * {@code POST /api/v1/admin/accounts/{id}/restore}: {@code DEACTIVATED} or {@code PENDING_DELETION} (also
     * after the grace period, as long as the purge has not run) becomes {@code ACTIVE}; an active account is
     * returned unchanged (no event). Every refresh token family is revoked and the session epoch incremented, so
     * no session from before the restore comes back to life: the owner has to sign in again.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AccountResponse restore(long accountId) {
        Account account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> ApiProblemException.notFound(NOT_FOUND, "Account not found."));
        AccountStatus previous = account.getStatus();
        if (previous == AccountStatus.ACTIVE) {
            return AccountResponse.of(account);
        }
        if (previous != AccountStatus.DEACTIVATED && previous != AccountStatus.PENDING_DELETION) {
            throw ApiProblemException.conflict(NOT_RESTORABLE, "This account can no longer be restored.");
        }
        Account restored = reactivate(account);
        int revoked = refreshTokens.revokeAll(accountId);
        audit.recordAction(SecurityEventType.ACCOUNT_RESTORED, accountId,
                Map.of("from", previous.name(), "revokedRefreshTokens", revoked));
        return AccountResponse.of(restored);
    }

    /** {@code ACTIVE} again, lifecycle dates cleared, session epoch incremented (earlier access tokens end). */
    private Account reactivate(Account account) {
        account.setStatus(AccountStatus.ACTIVE);
        account.setDeletedAt(null);
        account.setDeleteAfter(null);
        account.setSessionEpoch(account.getSessionEpoch() + 1);
        return accounts.saveAndFlush(account);
    }

    private static boolean isLastActiveAdmin(List<Long> activeAdmins, long accountId) {
        return activeAdmins.stream().noneMatch(id -> id != accountId);
    }

    private static ApiProblemException lastAdmin() {
        return ApiProblemException.conflict(LAST_ADMIN, "The last active administrator cannot be removed.");
    }
}
