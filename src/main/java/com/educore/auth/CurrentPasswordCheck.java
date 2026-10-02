package com.educore.auth;

import com.educore.common.web.ApiProblemException;
import com.educore.entity.Account;
import com.educore.security.ActiveAccount;
import com.educore.security.audit.AuditService;
import com.educore.security.audit.SecurityEventType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Re-authentication with the current password for sensitive self-service actions (password change, deletion
 * request, restore of an account pending deletion). Runs inside the caller's transaction with the same locks and lockout as a login:
 * the per-username advisory lock ({@link AttemptLocks}), the (username, client) lockout and the per-account
 * progressive delay ({@link LoginAttemptService}), the account row lock
 * ({@link AccountLocks}) and a constant-work password check ({@link CredentialVerifier}). A wrong password is
 * recorded as a failed attempt (counts towards the lockout) with an {@code AUTH_LOGIN_FAILURE} event.
 * <p>
 * The result carries the problem instead of throwing it, so the caller can commit the recorded failure and
 * throw afterwards. {@link AttemptLocks.Busy} propagates; the caller rolls back and answers
 * {@link #busyProblem()}.
 */
@Component
public class CurrentPasswordCheck {

    /** Retry-After when parallel requests for one username queue longer than {@link AttemptLocks#WAIT}. */
    static final Duration BUSY_RETRY_AFTER = Duration.ofSeconds(5);

    private final AttemptLocks attemptLocks;
    private final AccountLocks accountLocks;
    private final LoginAttemptService attempts;
    private final CredentialVerifier credentialVerifier;
    private final UsernameHasher usernameHasher;
    private final AuditService events;

    public CurrentPasswordCheck(AttemptLocks attemptLocks, AccountLocks accountLocks, LoginAttemptService attempts,
                                CredentialVerifier credentialVerifier, UsernameHasher usernameHasher,
                                AuditService events) {
        this.attemptLocks = attemptLocks;
        this.accountLocks = accountLocks;
        this.attempts = attempts;
        this.credentialVerifier = credentialVerifier;
        this.usernameHasher = usernameHasher;
        this.events = events;
    }

    /** Outcome of {@link #verify}: the locked, active account, or the problem to answer. */
    public record Result(Account account, ApiProblemException problem) {

        public boolean verified() {
            return problem == null;
        }
    }

    /**
     * Verifies {@code currentPassword} of the active account {@code accountId} whose username is
     * {@code username} (read before the transaction; the lock key). The account row stays locked
     * ({@code FOR NO KEY UPDATE}) until the transaction ends. A successful check records nothing; the caller
     * records the success with {@link #recordSuccess} once its own preconditions hold.
     *
     * @param failureReason the {@code reason} of the {@code AUTH_LOGIN_FAILURE} event of a wrong password
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result verify(long accountId, String username, String currentPassword, ClientInfo client,
                         String failureReason) {
        return verify(accountId, username, currentPassword, client, failureReason, ActiveAccount::isActive);
    }

    /**
     * {@link #verify(long, String, String, ClientInfo, String)} for an account that satisfies {@code eligible}
     * (for example an account in its deletion grace period restoring itself) instead of an active one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result verify(long accountId, String username, String currentPassword, ClientInfo client,
                         String failureReason, Predicate<Account> eligible) {
        String usernameHash = usernameHasher.hash(username);
        attemptLocks.lock(usernameHash);
        Optional<Duration> lock = attempts.remainingLock(usernameHash, client.ip());
        if (lock.isPresent()) {
            return new Result(null, AuthProblemException.locked(lock.get()));
        }
        Optional<Duration> wait = attempts.throttleDelay(usernameHash, client.ip());
        if (wait.isPresent()) {
            return new Result(null, AuthProblemException.tooManyAttempts(wait.get()));
        }
        Account account = accountLocks.lockById(accountId).filter(eligible).orElse(null);
        if (account == null || !account.getUsername().equals(username)) {
            return new Result(null, AuthProblemException.invalidCredentials());
        }
        if (!credentialVerifier.verify(currentPassword, account.getPassword()).matched()) {
            recordFailure(usernameHash, account.getId(), client, failureReason);
            return new Result(null, AuthProblemException.invalidCurrentPassword());
        }
        return new Result(account, null);
    }

    /** Records a successful verification of {@code username} (ends a failure streak). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSuccess(String username, ClientInfo client) {
        attempts.record(usernameHasher.hash(username), client.ip(), true);
    }

    /** The answer when the username's attempt lock stayed busy ({@link AttemptLocks.Busy}). */
    public static ApiProblemException busyProblem() {
        return AuthProblemException.tooManyAttempts(BUSY_RETRY_AFTER);
    }

    /**
     * Records a failed verification: the attempt, an {@code AUTH_LOGIN_FAILURE} event and, when this failure
     * starts a lock, an {@code AUTH_LOCKED} event.
     */
    void recordFailure(String usernameHash, Long accountId, ClientInfo client, String reason) {
        attempts.record(usernameHash, client.ip(), false);
        events.record(SecurityEventType.AUTH_LOGIN_FAILURE, null, accountId, client.ip(),
                Map.of("reason", reason, "usernameHash", usernameHash));
        attempts.remainingLock(usernameHash, client.ip()).ifPresent(remaining -> {
            Map<String, Object> details = new HashMap<>();
            details.put("usernameHash", usernameHash);
            details.put("lockSeconds", remaining.toSeconds());
            events.record(SecurityEventType.AUTH_LOCKED, null, accountId, client.ip(), details);
        });
    }
}
