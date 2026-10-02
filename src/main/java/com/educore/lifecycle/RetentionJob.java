package com.educore.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Deletes {@code login_attempt} rows older than {@code educore.lifecycle.retention-days.login-attempts} (90)
 * and {@code security_event} rows older than {@code retention-days.security-events} (365), on the schedule
 * {@code educore.lifecycle.retention-cron} (UTC; default nightly at 04:00). Lockout only reads attempts of the
 * last lock duration (15 minutes), so the cut-off never changes a lock. Each table is cleaned in its own
 * transaction; running on several instances at once only repeats an idempotent DELETE.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final Duration loginAttemptRetention;
    private final Duration securityEventRetention;

    public RetentionJob(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager transactionManager,
                        LifecycleProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.loginAttemptRetention = Duration.ofDays(properties.retentionDays().loginAttempts());
        this.securityEventRetention = Duration.ofDays(properties.retentionDays().securityEvents());
    }

    /** Rows removed by one run. */
    public record Result(int loginAttempts, int securityEvents) {
    }

    @Scheduled(cron = "${educore.lifecycle.retention-cron:0 0 4 * * *}", zone = "UTC")
    public void scheduledRun() {
        Result result = run();
        log.info("Retention cleanup finished loginAttempts={} securityEvents={}", result.loginAttempts(),
                result.securityEvents());
    }

    public Result run() {
        Instant now = clock.instant();
        Timestamp attemptsBefore = Timestamp.from(now.minus(loginAttemptRetention));
        Timestamp eventsBefore = Timestamp.from(now.minus(securityEventRetention));
        Integer attempts = transaction.execute(status ->
                jdbc.update("DELETE FROM login_attempt WHERE at < ?", attemptsBefore));
        Integer events = transaction.execute(status ->
                jdbc.update("DELETE FROM security_event WHERE at < ?", eventsBefore));
        return new Result(attempts == null ? 0 : attempts, events == null ? 0 : events);
    }
}
