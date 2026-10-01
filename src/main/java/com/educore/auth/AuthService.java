package com.educore.auth;

import com.educore.entity.Account;
import com.educore.repository.AccountRepository;
import com.educore.security.ActiveAccount;
import com.educore.security.AuthenticatedUser;
import com.educore.security.JwtService;
import com.educore.security.audit.AuditService;
import com.educore.security.audit.SecurityEventType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Login, refresh, logout, current user and password change.
 * <p>
 * Each flow runs in one transaction that returns its outcome instead of throwing, so attempts and security
 * events are committed even when the request ends in an error response; the error is thrown after commit.
 * <p>
 * Locking, always in this order: the per-username advisory lock ({@link AttemptLocks}: lockout check,
 * verification and attempt record are atomic), the account row ({@link AccountLocks}: credential check,
 * session issuance and password change are serialised per account), then refresh token families
 * ({@link RefreshTokenService}). A password change therefore revokes every session, including one being
 * issued by a concurrent login with the old password, and a failed revocation rolls the change back.
 */
@Service
public class AuthService {

    /** Retry-After when parallel requests for one username queue longer than {@link AttemptLocks#WAIT}. */
    private static final Duration BUSY_RETRY_AFTER = Duration.ofSeconds(5);

    private final AccountRepository accountRepository;
    private final AccountLocks accountLocks;
    private final AttemptLocks attemptLocks;
    private final CredentialVerifier credentialVerifier;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;
    private final LoginAttemptService attempts;
    private final LoginRateLimiter rateLimiter;
    private final UsernameHasher usernameHasher;
    private final PasswordPolicy passwordPolicy;
    private final AuditService events;
    private final TransactionTemplate transaction;

    public AuthService(AccountRepository accountRepository, AccountLocks accountLocks, AttemptLocks attemptLocks,
                       CredentialVerifier credentialVerifier, PasswordEncoder passwordEncoder, JwtService jwtService,
                       RefreshTokenService refreshTokens, LoginAttemptService attempts, LoginRateLimiter rateLimiter,
                       UsernameHasher usernameHasher, PasswordPolicy passwordPolicy, AuditService events,
                       PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.accountLocks = accountLocks;
        this.attemptLocks = attemptLocks;
        this.credentialVerifier = credentialVerifier;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.attempts = attempts;
        this.rateLimiter = rateLimiter;
        this.usernameHasher = usernameHasher;
        this.passwordPolicy = passwordPolicy;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** A new access token plus the refresh token value for the cookie. */
    public record Session(AuthResponse body, String refreshToken) {
    }

    /** What a transaction produced: a session, or the problem to throw once it has committed. */
    private record Outcome(Session session, AuthProblemException problem) {

        static Outcome of(Session session) {
            return new Outcome(session, null);
        }

        static Outcome failed(AuthProblemException problem) {
            return new Outcome(null, problem);
        }

        Session sessionOrThrow() {
            if (problem != null) {
                throw problem;
            }
            return session;
        }
    }

    public Session login(LoginRequest request, ClientInfo client) {
        LoginRateLimiter.Decision decision = rateLimiter.tryAcquire(client.ip());
        if (!decision.allowed()) {
            throw AuthProblemException.tooManyAttempts(decision.retryAfter());
        }
        String usernameHash = usernameHasher.hash(request.username());
        return inTransaction(() -> loginLocked(request, usernameHash, client)).sessionOrThrow();
    }

    private Outcome loginLocked(LoginRequest request, String usernameHash, ClientInfo client) {
        attemptLocks.lock(usernameHash);
        Optional<Duration> lock = attempts.remainingLock(usernameHash);
        if (lock.isPresent()) {
            events.record(SecurityEventType.AUTH_LOGIN_FAILURE, null, null, client.ip(),
                    Map.of("reason", "locked", "usernameHash", usernameHash));
            return Outcome.failed(AuthProblemException.locked(lock.get()));
        }

        Account account = accountLocks.lockByUsername(request.username()).orElse(null);
        // Same bcrypt work for unknown users, legacy hashes and current hashes (CredentialVerifier).
        boolean matched = credentialVerifier.verify(request.password(),
                account == null ? null : account.getPassword()).matched();
        if (!matched || !ActiveAccount.isActive(account)) {
            Long target = account == null ? null : account.getId();
            recordFailure(usernameHash, target, client, matched ? "inactive_account" : "bad_credentials");
            return Outcome.failed(AuthProblemException.invalidCredentials());
        }

        attempts.record(usernameHash, client.ip(), true);
        upgradeHashIfNeeded(account, request.password());
        events.record(SecurityEventType.AUTH_LOGIN_SUCCESS, account.getId(), account.getId(), client.ip(), Map.of());
        return Outcome.of(newSession(account, client));
    }

    public Session refresh(String refreshToken, ClientInfo client) {
        return inTransaction(() -> {
            RefreshTokenService.Rotation rotation = refreshTokens.rotate(refreshToken, client);
            if (rotation instanceof RefreshTokenService.Rotation.Rotated rotated) {
                Account account = accountRepository.findById(rotated.accountId()).orElse(null);
                if (ActiveAccount.isActive(account)) {
                    return Outcome.of(new Session(accessFor(account), rotated.value()));
                }
                // Inactive account: undo the rotation, nothing is issued.
                throw new InactiveAccountRefresh();
            }
            // Rejected or reused: commit (reuse revoked the family and recorded the event).
            return Outcome.failed(AuthProblemException.invalidRefreshToken());
        }).sessionOrThrow();
    }

    public void logout(String refreshToken) {
        refreshTokens.revokeFamilyOf(refreshToken);
    }

    public UserView me(AuthenticatedUser user) {
        return accountRepository.findById(user.id()).filter(ActiveAccount::isActive).map(UserView::of)
                .orElseThrow(AuthProblemException::invalidCredentials);
    }

    /**
     * Verifies the current password (failures count towards the lockout), applies the policy, stores the new
     * hash, clears {@code mustChangePassword}, revokes every refresh token of the account and starts a new
     * session, all in one transaction under the account lock.
     */
    public Session changePassword(AuthenticatedUser user, PasswordChangeRequest request, ClientInfo client) {
        String username = accountRepository.findById(user.id()).filter(ActiveAccount::isActive)
                .map(Account::getUsername).orElseThrow(AuthProblemException::invalidCredentials);
        String usernameHash = usernameHasher.hash(username);
        return inTransaction(() -> changePasswordLocked(user.id(), usernameHash, request, client)).sessionOrThrow();
    }

    private Outcome changePasswordLocked(long accountId, String usernameHash, PasswordChangeRequest request,
                                         ClientInfo client) {
        attemptLocks.lock(usernameHash);
        Optional<Duration> lock = attempts.remainingLock(usernameHash);
        if (lock.isPresent()) {
            return Outcome.failed(AuthProblemException.locked(lock.get()));
        }
        Account account = accountLocks.lockById(accountId).filter(ActiveAccount::isActive).orElse(null);
        if (account == null) {
            return Outcome.failed(AuthProblemException.invalidCredentials());
        }
        if (!credentialVerifier.verify(request.currentPassword(), account.getPassword()).matched()) {
            recordFailure(usernameHash, account.getId(), client, "password_change_bad_current");
            return Outcome.failed(AuthProblemException.invalidCurrentPassword());
        }
        List<String> violations = new ArrayList<>(passwordPolicy.violations(request.newPassword()));
        if (request.newPassword().equals(request.currentPassword())) {
            violations.add("same_as_current");
        }
        if (!violations.isEmpty()) {
            return Outcome.failed(AuthProblemException.passwordPolicy(violations));
        }
        attempts.record(usernameHash, client.ip(), true);
        String newHash = passwordEncoder.encode(request.newPassword());
        if (!accountLocks.compareAndSetPassword(account.getId(), account.getPassword(), newHash, true)) {
            // Cannot happen while the account row is locked; never fall back to writing a stale copy.
            throw new IllegalStateException("Password changed concurrently");
        }
        account.setPassword(newHash);
        account.setMustChangePassword(false);
        int revoked = refreshTokens.revokeAll(account.getId());
        events.record(SecurityEventType.PASSWORD_CHANGED, account.getId(), account.getId(), client.ip(),
                Map.of("revokedRefreshTokens", revoked));
        return Outcome.of(newSession(account, client));
    }

    private void recordFailure(String usernameHash, Long accountId, ClientInfo client, String reason) {
        attempts.record(usernameHash, client.ip(), false);
        events.record(SecurityEventType.AUTH_LOGIN_FAILURE, null, accountId, client.ip(),
                Map.of("reason", reason, "usernameHash", usernameHash));
        attempts.remainingLock(usernameHash).ifPresent(remaining -> {
            Map<String, Object> details = new HashMap<>();
            details.put("usernameHash", usernameHash);
            details.put("lockSeconds", remaining.toSeconds());
            events.record(SecurityEventType.AUTH_LOCKED, null, accountId, client.ip(), details);
        });
    }

    /**
     * Re-hashes legacy hashes (no {@code {bcrypt}} prefix or a lower bcrypt cost) with the current encoder.
     * Compare-and-set on the verified hash: a password changed in the meantime is never overwritten.
     */
    private void upgradeHashIfNeeded(Account account, String rawPassword) {
        if (passwordEncoder.upgradeEncoding(account.getPassword())
                && rawPassword.getBytes(StandardCharsets.UTF_8).length <= PasswordPolicy.MAX_BCRYPT_BYTES) {
            String upgraded = passwordEncoder.encode(rawPassword);
            if (accountLocks.compareAndSetPassword(account.getId(), account.getPassword(), upgraded, false)) {
                account.setPassword(upgraded);
            }
        }
    }

    private Session newSession(Account account, ClientInfo client) {
        AuthResponse body = accessFor(account);
        String refreshToken = refreshTokens.issueNewFamily(account.getId(), client);
        return new Session(body, refreshToken);
    }

    private AuthResponse accessFor(Account account) {
        JwtService.IssuedAccessToken token = jwtService.issue(AuthenticatedUser.of(account));
        return new AuthResponse(token.token(), token.expiresInSeconds(), UserView.of(account));
    }

    private Outcome inTransaction(Supplier<Outcome> work) {
        try {
            return transaction.execute(status -> work.get());
        } catch (InactiveAccountRefresh e) {
            return Outcome.failed(AuthProblemException.invalidRefreshToken());
        } catch (AttemptLocks.Busy e) {
            return Outcome.failed(AuthProblemException.tooManyAttempts(BUSY_RETRY_AFTER));
        }
    }

    /** Rolls back a rotation whose account is no longer active. */
    private static final class InactiveAccountRefresh extends RuntimeException {
        InactiveAccountRefresh() {
            super(null, null, false, false);
        }
    }
}
