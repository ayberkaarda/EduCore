package com.educore.lifecycle;

import com.educore.auth.UsernameHasher;
import com.educore.ingestion.IngestionRetention;
import com.educore.ingestion.PiiMasker;
import com.educore.security.audit.AuditService;
import com.educore.security.audit.SecurityEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hard-deletes one account and everything that identifies its owner, in the caller's transaction. The caller
 * holds the account's row lock ({@code FOR UPDATE}) and has applied its own guards (grace period, last ADMIN,
 * confirmation).
 * <ol>
 *   <li>{@code account.status = DELETED} (the row is removed at the end of the same transaction);</li>
 *   <li>{@code security_event}: the client IP of events the account caused is removed, the account id in
 *       {@code actor_account_id}/{@code target_account_id} is replaced by its pseudonym
 *       ({@link Pseudonyms}), and the {@code usernameHash} detail of failed logins against its username is
 *       removed;</li>
 *   <li>{@code login_attempt} rows of its username (linked through the peppered username hash) are deleted;</li>
 *   <li>enrollments, refresh tokens and refresh token families are deleted, then the account row;</li>
 *   <li>residual traces (AC-08): {@code webhook_delivery} rows whose payload names the account id are deleted;
 *       {@code job_log_entry.raw_masked} values equal to the masked form of the account's CSV line (first
 *       character of each value, e.g. {@code A***,Y***,2***}) are cleared; after the commit the lines carrying its
 *       student number are removed from the CSV files still kept in {@code done/} and {@code failed/}
 *       ({@link IngestionRetention#scrub});</li>
 *   <li>the purge is entered in the {@link ErasureLedger} (table, and append-only file outside the database
 *       dump), so a restored backup cannot bring the account back ({@link ErasureLedgerReplay});</li>
 *   <li>one {@code ACCOUNT_PURGED} event (target = pseudonym; counts only) is written and
 *       {@link AccountPurged} is published; {@link AccountDeletedWebhookRelay} queues the {@code account.deleted}
 *       webhook after commit.</li>
 * </ol>
 * Nothing else stores the account id: {@code ip_deny_rule.created_by} and {@code webhook_subscription.created_by}
 * keep the bare id of an ADMIN, which no longer resolves to a person.
 */
@Component
public class AccountPurger {

    /** Why an account was purged ({@code details.trigger} of {@code ACCOUNT_PURGED}). */
    public enum Trigger {
        /** The grace period of a deletion requested by the owner ended ({@link AccountPurgeJob}). */
        GRACE_EXPIRED,
        /** An ADMIN purged the account ({@code POST /api/v1/admin/accounts/{id}/purge}). */
        ADMIN_HARD_DELETE,
        /** A restored database still held an account the erasure ledger lists ({@link ErasureLedgerReplay}). */
        LEDGER_REPLAY
    }

    private static final Logger log = LoggerFactory.getLogger(AccountPurger.class);

    private static final String LOCK_ACCOUNT = "SELECT username, first_name, last_name, student_number FROM account "
            + "WHERE id = ? FOR UPDATE";
    private static final String DELETE_DELIVERIES = "DELETE FROM webhook_delivery "
            + "WHERE payload -> 'data' ->> 'accountId' = ?";
    private static final String CLEAR_MASKED_ROWS = "UPDATE job_log_entry SET raw_masked = NULL "
            + "WHERE raw_masked IN (?, ?)";

    private record Identity(String username, String firstName, String lastName, String studentNumber) {
    }

    private final JdbcTemplate jdbc;
    private final Pseudonyms pseudonyms;
    private final UsernameHasher usernameHasher;
    private final AuditService audit;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final ErasureLedger ledger;
    private final IngestionRetention ingestionFiles;

    public AccountPurger(JdbcTemplate jdbc, Pseudonyms pseudonyms, UsernameHasher usernameHasher, AuditService audit,
                         ApplicationEventPublisher publisher, Clock clock, ErasureLedger ledger,
                         IngestionRetention ingestionFiles) {
        this.jdbc = jdbc;
        this.pseudonyms = pseudonyms;
        this.usernameHasher = usernameHasher;
        this.audit = audit;
        this.publisher = publisher;
        this.clock = clock;
        this.ledger = ledger;
        this.ingestionFiles = ingestionFiles;
    }

    /** Counts of what one purge removed or rewrote. */
    public record Result(String pseudonym, int enrollments, int refreshTokens, int loginAttempts,
                         int securityEvents) {
    }

    /**
     * Purges {@code accountId}; returns {@code null} when the row no longer exists (already purged by a
     * concurrent run that committed first).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result purge(long accountId, Trigger trigger) {
        List<Identity> identities = jdbc.query(LOCK_ACCOUNT, (rs, n) -> new Identity(rs.getString("username"),
                rs.getString("first_name"), rs.getString("last_name"), rs.getString("student_number")), accountId);
        if (identities.isEmpty()) {
            return null;
        }
        Identity identity = identities.get(0);
        String pseudonym = pseudonyms.ofAccount(accountId);
        String usernameHash = usernameHasher.hash(identity.username());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        jdbc.update("UPDATE account SET status = 'DELETED', deleted_at = COALESCE(deleted_at, ?) WHERE id = ?",
                Timestamp.from(now), accountId);
        jdbc.update("UPDATE security_event SET ip = NULL WHERE actor_account_id = ?", accountId);
        int asActor = jdbc.update("UPDATE security_event SET actor_account_id = NULL, actor_pseudonym = ? "
                + "WHERE actor_account_id = ?", pseudonym, accountId);
        int asTarget = jdbc.update("UPDATE security_event SET target_account_id = NULL, target_pseudonym = ? "
                + "WHERE target_account_id = ?", pseudonym, accountId);
        jdbc.update("UPDATE security_event SET details = details - 'usernameHash' "
                + "WHERE details ->> 'usernameHash' = ?", usernameHash);
        int loginAttempts = jdbc.update("DELETE FROM login_attempt WHERE username_hash = ?", usernameHash);
        int enrollments = jdbc.update("DELETE FROM enrollments WHERE account_id = ?", accountId);
        int refreshTokens = jdbc.update("DELETE FROM refresh_token WHERE account_id = ?", accountId);
        jdbc.update("DELETE FROM refresh_token_family WHERE account_id = ?", accountId);
        jdbc.update("DELETE FROM account WHERE id = ?", accountId);
        int deliveries = jdbc.update(DELETE_DELIVERIES, String.valueOf(accountId));
        int maskedRows = clearMaskedRows(identity);
        ledger.record(accountId, identity.username(), identity.studentNumber(), now);
        scrubFilesAfterCommit(identity.studentNumber());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("trigger", trigger.name());
        details.put("enrollments", enrollments);
        details.put("refreshTokens", refreshTokens);
        details.put("loginAttempts", loginAttempts);
        details.put("securityEvents", asActor + asTarget);
        details.put("webhookDeliveries", deliveries);
        details.put("jobLogEntries", maskedRows);
        audit.recordAboutPurgedAccount(SecurityEventType.ACCOUNT_PURGED, pseudonym, details);
        publisher.publishEvent(new AccountPurged(accountId, trigger));
        log.info("ACCOUNT_PURGED pseudonym={} trigger={}", pseudonym, trigger);
        return new Result(pseudonym, enrollments, refreshTokens, loginAttempts, asActor + asTarget);
    }

    /**
     * Masked import rows keep only the first character of each value, so they cannot be traced to one person
     * reliably; the rows whose mask equals the account's own CSV line (plain or fully quoted) are cleared anyway.
     * Row number and reason code stay for the operator.
     */
    private int clearMaskedRows(Identity identity) {
        if (identity.studentNumber() == null || identity.studentNumber().isBlank()) {
            return 0;
        }
        String first = identity.firstName() == null ? "" : identity.firstName();
        String last = identity.lastName() == null ? "" : identity.lastName();
        String plain = PiiMasker.mask(first + "," + last + "," + identity.studentNumber());
        String quoted = PiiMasker.mask('"' + first + "\",\"" + last + "\",\"" + identity.studentNumber() + '"');
        return jdbc.update(CLEAR_MASKED_ROWS, plain, quoted);
    }

    /** File system changes are not transactional: they run only once the purge committed. */
    private void scrubFilesAfterCommit(String studentNumber) {
        if (studentNumber == null || studentNumber.isBlank()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    IngestionRetention.Scrub scrub = ingestionFiles.scrub(studentNumber);
                    if (scrub.linesRemoved() + scrub.filesDeleted() > 0) {
                        log.info("Purged account removed from kept import files filesRewritten={} "
                                        + "filesDeleted={} linesRemoved={}", scrub.filesRewritten(),
                                scrub.filesDeleted(), scrub.linesRemoved());
                    }
                } catch (RuntimeException e) {
                    // The retention cleanup still deletes the files when their period ends.
                    log.error("Import files could not be scrubbed after a purge error={}", e.getClass().getName());
                }
            }
        });
    }
}
