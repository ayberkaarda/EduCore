package com.educore.auth;

import com.educore.config.EduCoreProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Records login attempts and derives the account lock from them.
 * <p>
 * An account (more precisely: a username hash, so unknown usernames behave the same) is locked when its
 * last {@code max-failures} attempts (default 5) are all failures that happened within
 * {@code lock-duration} (default 15 minutes) of each other; the lock ends {@code lock-duration} after the
 * newest of them. Attempts rejected while locked are not recorded, so they do not extend the lock, and a
 * successful login ends the failure streak. No lock column is kept on {@code account}: the state cannot
 * drift from the attempt history and no account row is written on a failed login.
 */
@Service
public class LoginAttemptService {

    private final LoginAttemptRepository repository;
    private final Clock clock;
    private final int maxFailures;
    private final Duration lockDuration;

    public LoginAttemptService(LoginAttemptRepository repository, Clock clock, EduCoreProperties properties) {
        this.repository = repository;
        this.clock = clock;
        this.maxFailures = properties.security().login().maxFailures();
        this.lockDuration = properties.security().login().lockDuration();
    }

    public void record(String usernameHash, String ip, boolean success) {
        repository.save(new LoginAttempt(usernameHash, ip, success, clock.instant()));
    }

    /** Remaining lock time for {@code usernameHash}, or empty when attempts are allowed. */
    public Optional<Duration> remainingLock(String usernameHash) {
        List<LoginAttempt> recent = repository.findByUsernameHashOrderByAtDescIdDesc(
                usernameHash, PageRequest.of(0, maxFailures));
        if (recent.size() < maxFailures || recent.stream().anyMatch(LoginAttempt::isSuccess)) {
            return Optional.empty();
        }
        Instant newest = recent.get(0).getAt();
        Instant oldest = recent.get(recent.size() - 1).getAt();
        if (Duration.between(oldest, newest).compareTo(lockDuration) > 0) {
            return Optional.empty();
        }
        Duration remaining = Duration.between(clock.instant(), newest.plus(lockDuration));
        return remaining.isNegative() || remaining.isZero() ? Optional.empty() : Optional.of(remaining);
    }
}
