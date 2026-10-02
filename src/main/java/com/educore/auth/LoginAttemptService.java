package com.educore.auth;

import com.educore.config.EduCoreProperties;
import com.educore.ipaccess.ClientAddress;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Records login attempts and derives the lockout and the per-account throttle from them (R-01, AC-01).
 * <p>
 * <b>Hard lock per (username, client) pair.</b> The pair of a username hash (unknown usernames behave the same)
 * and a canonical client key ({@link ClientAddress#clientKey}: IPv4 address or IPv6 /64) is locked when its last
 * {@code max-failures} attempts (default 5) are all failures within {@code lock-duration} (default 15 minutes) of
 * each other; the lock ends {@code lock-duration} after the newest of them. Failures from one network therefore
 * lock only that network out of that account: an attacker can no longer lock the owner (or the administrator)
 * out from everywhere.
 * <p>
 * <b>Progressive delay per username across all clients</b> ({@code educore.security.login.account-throttle}):
 * after {@code free-failures} consecutive failures within {@code window} since the last success, the next
 * verification must wait {@code base-delay * 2^(n - free-failures)} (at most {@code max-delay}, 30 s) after the
 * newest failure, so distributed guessing is slowed to about two guesses a minute. It is a rate limit, not a
 * lock: it never lasts longer than {@code max-delay} after the last failure, and a client key that signed in to
 * the account successfully within {@code trusted-network-age} is exempt.
 * <p>
 * Attempts rejected by either rule are not recorded, so they extend neither; a successful login ends the failure
 * streak. No lock column is kept on {@code account}: the state cannot drift from the attempt history and no
 * account row is written on a failed login.
 */
@Service
public class LoginAttemptService {

    /** Rows read for the throttle: enough consecutive failures to reach the maximum delay. */
    private static final int THROTTLE_ROWS = 64;

    private final LoginAttemptRepository repository;
    private final Clock clock;
    private final int maxFailures;
    private final Duration lockDuration;
    private final EduCoreProperties.AccountThrottle throttle;

    public LoginAttemptService(LoginAttemptRepository repository, Clock clock, EduCoreProperties properties) {
        this.repository = repository;
        this.clock = clock;
        this.maxFailures = properties.security().login().maxFailures();
        this.lockDuration = properties.security().login().lockDuration();
        this.throttle = properties.security().login().accountThrottle();
    }

    public void record(String usernameHash, String ip, boolean success) {
        repository.save(new LoginAttempt(usernameHash, ip, ClientAddress.clientKey(ip), success, clock.instant()));
    }

    /** Remaining lock time of the pair ({@code usernameHash}, client key of {@code ip}), or empty. */
    public Optional<Duration> remainingLock(String usernameHash, String ip) {
        List<LoginAttempt> recent = repository.findByUsernameHashAndClientKeyOrderByAtDescIdDesc(
                usernameHash, ClientAddress.clientKey(ip), PageRequest.of(0, maxFailures));
        if (recent.size() < maxFailures || recent.stream().anyMatch(LoginAttempt::isSuccess)) {
            return Optional.empty();
        }
        Instant newest = recent.get(0).getAt();
        Instant oldest = recent.get(recent.size() - 1).getAt();
        if (Duration.between(oldest, newest).compareTo(lockDuration) > 0) {
            return Optional.empty();
        }
        return remaining(newest.plus(lockDuration));
    }

    /**
     * How long a verification of {@code usernameHash} from {@code ip} must still wait under the per-account
     * progressive delay, or empty when it may run now.
     */
    public Optional<Duration> throttleDelay(String usernameHash, String ip) {
        Instant now = clock.instant();
        List<LoginAttempt> recent = repository.findByUsernameHashOrderByAtDescIdDesc(
                usernameHash, PageRequest.of(0, THROTTLE_ROWS));
        Instant windowStart = now.minus(throttle.window());
        int failures = 0;
        for (LoginAttempt attempt : recent) {
            if (attempt.isSuccess() || !attempt.getAt().isAfter(windowStart)) {
                break;
            }
            failures++;
        }
        if (failures < Math.max(1, throttle.freeFailures())) {
            return Optional.empty();
        }
        Duration delay = delayAfter(failures);
        Optional<Duration> wait = remaining(recent.get(0).getAt().plus(delay));
        if (wait.isEmpty() || repository.existsByUsernameHashAndClientKeyAndSuccessTrueAndAtAfter(usernameHash,
                ClientAddress.clientKey(ip), now.minus(throttle.trustedNetworkAge()))) {
            return Optional.empty();
        }
        return wait;
    }

    /** The delay after {@code failures} consecutive failures: base * 2^(failures - free), capped. */
    Duration delayAfter(int failures) {
        int exponent = Math.min(30, Math.max(0, failures - throttle.freeFailures()));
        Duration delay = throttle.baseDelay().multipliedBy(1L << exponent);
        return delay.compareTo(throttle.maxDelay()) > 0 ? throttle.maxDelay() : delay;
    }

    /** Deletes every failed attempt of {@code usernameHash} (ADMIN unlock); returns how many were removed. */
    public int clearFailures(String usernameHash) {
        return repository.deleteFailures(usernameHash);
    }

    private Optional<Duration> remaining(Instant until) {
        Duration remaining = Duration.between(clock.instant(), until);
        return remaining.isNegative() || remaining.isZero() ? Optional.empty() : Optional.of(remaining);
    }
}
