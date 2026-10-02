package com.educore.ingestion;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Owner, lease and state of manual uploads waiting in {@code staging/} ({@code upload_staging}, AC-16).
 * <ol>
 *   <li>{@link #begin}: a STAGING row owned by the uploading instance is committed in its own transaction
 *       before the file is written;</li>
 *   <li>{@link #markCommitted}: inside the upload transaction, together with the {@code IMPORT_UPLOADED} event;</li>
 *   <li>{@link #remove}: after the file was published (commit) or discarded (rollback), in its own
 *       transaction.</li>
 * </ol>
 * Recovery acts only on rows whose lease expired, through {@link #claimAbandoned}, a conditional delete: of an
 * owner finishing late and a recovery, exactly one wins.
 */
@Component
public class UploadStaging {

    private static final String INSERT = "INSERT INTO upload_staging (token, owner, state, lease_until, created_at) "
            + "VALUES (?, ?, 'STAGING', ?, ?)";
    private static final String MARK_COMMITTED = "UPDATE upload_staging SET state = 'COMMITTED' "
            + "WHERE token = ? AND owner = ? AND state = 'STAGING'";
    private static final String DELETE = "DELETE FROM upload_staging WHERE token = ?";
    private static final String RENEW = "UPDATE upload_staging SET lease_until = ? "
            + "WHERE owner = ? AND lease_until >= ?";
    private static final String FIND = "SELECT state, lease_until FROM upload_staging WHERE token = ?";
    private static final String CLAIM_ABANDONED = "DELETE FROM upload_staging "
            + "WHERE token = ? AND state = ? AND lease_until < ?";
    private static final String EXPIRED_TOKENS = "SELECT token FROM upload_staging WHERE lease_until < ?";

    /** State of a staged upload. */
    public enum State { STAGING, COMMITTED }

    /** A staging row as recovery sees it. */
    public record Row(State state, Instant leaseUntil) {

        public boolean leaseExpired(Instant now) {
            return leaseUntil.isBefore(now);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate ownTransaction;

    public UploadStaging(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Commits a STAGING row for {@code token} in its own transaction (before the file exists). */
    public void begin(String token, String owner, Instant leaseUntil, Instant now) {
        ownTransaction.executeWithoutResult(status -> jdbc.update(INSERT, UUID.fromString(token), owner,
                Timestamp.from(leaseUntil), Timestamp.from(now)));
    }

    /**
     * Marks the upload COMMITTED in the caller's (upload) transaction.
     *
     * @throws IllegalStateException when the row is gone (recovery discarded the abandoned upload)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markCommitted(String token, String owner) {
        if (jdbc.update(MARK_COMMITTED, UUID.fromString(token), owner) != 1) {
            throw new IllegalStateException("Staged upload is no longer owned by this instance");
        }
    }

    /** Deletes the row in its own transaction (after publish or discard). */
    public void remove(String token) {
        ownTransaction.executeWithoutResult(status -> jdbc.update(DELETE, UUID.fromString(token)));
    }

    /** Heartbeat: extends the unexpired leases of {@code owner}'s uploads. */
    @Transactional
    public int renewLeases(String owner, Instant now, Instant until) {
        return jdbc.update(RENEW, Timestamp.from(until), owner, Timestamp.from(now));
    }

    @Transactional(readOnly = true)
    public Optional<Row> find(String token) {
        return jdbc.query(FIND, (rs, n) -> new Row(State.valueOf(rs.getString("state")),
                rs.getTimestamp("lease_until").toInstant()), UUID.fromString(token)).stream().findFirst();
    }

    /**
     * Takes over an abandoned upload: deletes its row if it is still in {@code state} and its lease is still
     * expired. Only a caller that gets {@code true} may publish or discard the file.
     */
    @Transactional
    public boolean claimAbandoned(String token, State state, Instant now) {
        return jdbc.update(CLAIM_ABANDONED, UUID.fromString(token), state.name(), Timestamp.from(now)) == 1;
    }

    /** Tokens of rows whose lease expired (their file may already be gone). */
    @Transactional(readOnly = true)
    public List<String> expiredTokens(Instant now) {
        return jdbc.queryForList(EXPIRED_TOKENS, UUID.class, Timestamp.from(now)).stream().map(UUID::toString)
                .toList();
    }
}
