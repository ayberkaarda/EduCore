package com.educore.lifecycle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RetentionJob} deletes {@code login_attempt} rows older than 90 days and {@code security_event} rows
 * older than 365 days (the defaults), measured against the application clock, and keeps younger rows.
 */
class RetentionJobIT extends LifecycleIntegrationSupport {

    @Autowired
    private RetentionJob retentionJob;

    private final String marker = "retention-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void removeRows() {
        jdbc.update("DELETE FROM login_attempt WHERE ip = ?", "192.0.2.77");
        jdbc.update("DELETE FROM security_event WHERE request_id = ?", marker);
    }

    @Test
    void deletesOnlyRowsOlderThanTheirRetention() {
        Instant now = clock.instant();
        String hash = "a".repeat(56) + marker.substring(marker.length() - 8);
        insertAttempt(hash, now.minus(Duration.ofDays(91)));
        insertAttempt(hash, now.minus(Duration.ofDays(89)));
        insertEvent(now.minus(Duration.ofDays(366)));
        insertEvent(now.minus(Duration.ofDays(364)));

        RetentionJob.Result result = retentionJob.run();

        assertThat(result.loginAttempts()).isGreaterThanOrEqualTo(1);
        assertThat(result.securityEvents()).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT count(*) FROM login_attempt WHERE ip = ?", "192.0.2.77")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM login_attempt WHERE ip = ? AND at < ?", "192.0.2.77",
                Timestamp.from(now.minus(Duration.ofDays(90))))).isZero();
        assertThat(count("SELECT count(*) FROM security_event WHERE request_id = ?", marker)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM security_event WHERE request_id = ? AND at < ?", marker,
                Timestamp.from(now.minus(Duration.ofDays(365))))).isZero();
        // Nothing left to delete for these rows on a second run.
        retentionJob.run();
        assertThat(count("SELECT count(*) FROM login_attempt WHERE ip = ?", "192.0.2.77")).isEqualTo(1);
    }

    @Test
    void retentionFollowsTheApplicationClock() {
        Instant now = clock.instant();
        String hash = "b".repeat(56) + marker.substring(marker.length() - 8);
        insertAttempt(hash, now.minus(Duration.ofDays(1)));
        insertEvent(now.minus(Duration.ofDays(1)));

        retentionJob.run();
        assertThat(count("SELECT count(*) FROM login_attempt WHERE ip = ?", "192.0.2.77")).isEqualTo(1);

        clock.advance(Duration.ofDays(90));
        retentionJob.run();
        assertThat(count("SELECT count(*) FROM login_attempt WHERE ip = ?", "192.0.2.77")).isZero();
        assertThat(count("SELECT count(*) FROM security_event WHERE request_id = ?", marker)).isEqualTo(1);

        clock.advance(Duration.ofDays(275));
        retentionJob.run();
        assertThat(count("SELECT count(*) FROM security_event WHERE request_id = ?", marker)).isZero();
    }

    private void insertAttempt(String usernameHash, Instant at) {
        jdbc.update("INSERT INTO login_attempt (username_hash, ip, client_key, success, at) VALUES (?, ?, ?, false, ?)",
                usernameHash, "192.0.2.77", "192.0.2.77", Timestamp.from(at));
    }

    private void insertEvent(Instant at) {
        jdbc.update("INSERT INTO security_event (type, ip, request_id, at) VALUES ('AUTH_LOGIN_FAILURE', NULL, ?, ?)",
                marker, Timestamp.from(at));
    }
}
