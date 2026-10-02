package com.educore.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Replays the erasure ledger after a database restore (AC-09), at startup: after every bean exists and before
 * the web server accepts a request (a failure stops the start).
 * <p>
 * A restore is detected when (a) {@code scripts/backup/post-restore.sh} left a pending {@code restore_replay} row,
 * or (b) the ledger file holds entries the {@code erasure_ledger} table does not know (the database is older
 * than the file: a dump was restored without the script). Then:
 * <ol>
 *   <li>the file's entries are copied into the table;</li>
 *   <li>every account whose id digest is in the ledger is purged again (trigger {@code LEDGER_REPLAY}); an
 *       account that matches only by username or student number digest is not purged (after a purge the same
 *       student number may legitimately be registered again) but logged by pseudonym for review;</li>
 *   <li>every refresh token and family is revoked and every account's session epoch is incremented, so no
 *       session revoked after the dump was taken works again;</li>
 *   <li>the replay is recorded as completed.</li>
 * </ol>
 * The replay runs before any traffic, when the restored ids cannot yet have been reused by new accounts. Without
 * a restore it only appends table entries missing from the file (a new or replaced ledger volume).
 */
@Component
public class ErasureLedgerReplay implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ErasureLedgerReplay.class);

    private static final String PENDING = "SELECT id FROM restore_replay WHERE completed_at IS NULL ORDER BY id FOR UPDATE";
    private static final String ACCOUNTS = "SELECT id, username, student_number FROM account";
    private static final String REVOKE_TOKENS = "UPDATE refresh_token SET revoked_at = ? WHERE revoked_at IS NULL";
    private static final String REVOKE_FAMILIES = "UPDATE refresh_token_family SET revoked_at = ? "
            + "WHERE revoked_at IS NULL";
    private static final String BUMP_EPOCHS = "UPDATE account SET session_epoch = session_epoch + 1";
    private static final String COMPLETE = "UPDATE restore_replay SET completed_at = ?, accounts_purged = ?, "
            + "accounts_to_review = ? WHERE completed_at IS NULL";
    private static final String RECORD_DETECTED = "INSERT INTO restore_replay "
            + "(source, requested_at, completed_at, accounts_purged, accounts_to_review) "
            + "VALUES ('LEDGER_FILE_AHEAD', ?, ?, ?, ?)";

    /** Outcome of one {@link #replay()}. */
    public record Result(boolean restoreDetected, int fileEntriesImported, int accountsPurged, int accountsToReview,
                         int refreshTokensRevoked) {
    }

    private record Candidate(long id, String username, String studentNumber) {
    }

    private final ErasureLedger ledger;
    private final AccountPurger purger;
    private final Pseudonyms pseudonyms;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ErasureLedgerReplay(ErasureLedger ledger, AccountPurger purger, Pseudonyms pseudonyms, JdbcTemplate jdbc,
                               PlatformTransactionManager transactionManager, Clock clock) {
        this.ledger = ledger;
        this.purger = purger;
        this.pseudonyms = pseudonyms;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Result result = replay();
        if (result.restoreDetected()) {
            log.warn("Restore detected: erasure ledger replayed fileEntriesImported={} accountsPurged={} "
                            + "accountsToReview={} refreshTokensRevoked={}", result.fileEntriesImported(),
                    result.accountsPurged(), result.accountsToReview(), result.refreshTokensRevoked());
        }
    }

    /** Detects a restore and replays the ledger; see the class description. */
    public Result replay() {
        List<ErasureLedger.Entry> fromFile = ledger.fileEntries();
        Integer imported = transaction.execute(status -> {
            int inserted = 0;
            for (ErasureLedger.Entry entry : fromFile) {
                if (ledger.insert(entry)) {
                    inserted++;
                }
            }
            return inserted;
        });
        int fileEntriesImported = imported == null ? 0 : imported;
        List<Long> pending = transaction.execute(status -> jdbc.queryForList(PENDING, Long.class));
        boolean restoreDetected = fileEntriesImported > 0 || (pending != null && !pending.isEmpty());
        if (!restoreDetected) {
            ledger.appendMissingToFile(transaction.execute(status -> ledger.tableEntries()));
            return new Result(false, 0, 0, 0, 0);
        }

        List<ErasureLedger.Entry> entries = transaction.execute(status -> ledger.tableEntries());
        Set<String> accountDigests = new HashSet<>();
        Set<String> personDigests = new HashSet<>();
        for (ErasureLedger.Entry entry : entries == null ? List.<ErasureLedger.Entry>of() : entries) {
            accountDigests.add(entry.accountDigest());
            personDigests.add(entry.usernameDigest());
            if (entry.studentNumberDigest() != null) {
                personDigests.add(entry.studentNumberDigest());
            }
        }
        List<Candidate> accounts = jdbc.query(ACCOUNTS, (rs, n) -> new Candidate(rs.getLong("id"),
                rs.getString("username"), rs.getString("student_number")));
        int purged = 0;
        int toReview = 0;
        for (Candidate account : accounts) {
            if (accountDigests.contains(ledger.accountDigest(account.id()))) {
                AccountPurger.Result result = transaction.execute(status ->
                        purger.purge(account.id(), AccountPurger.Trigger.LEDGER_REPLAY));
                if (result != null) {
                    purged++;
                }
            } else if (personDigests.contains(ledger.usernameDigest(account.username()))
                    || personDigests.contains(ledger.studentNumberDigest(account.studentNumber()))) {
                toReview++;
                log.warn("Erasure ledger: a restored account matches a purged person but not the purged account; "
                        + "review it pseudonym={}", pseudonyms.ofAccount(account.id()));
            }
        }
        int finalPurged = purged;
        int finalToReview = toReview;
        Instant now = clock.instant();
        Integer revoked = transaction.execute(status -> {
            Timestamp at = Timestamp.from(now);
            int tokens = jdbc.update(REVOKE_TOKENS, at);
            jdbc.update(REVOKE_FAMILIES, at);
            jdbc.update(BUMP_EPOCHS);
            if (jdbc.update(COMPLETE, at, finalPurged, finalToReview) == 0) {
                jdbc.update(RECORD_DETECTED, at, at, finalPurged, finalToReview);
            }
            return tokens;
        });
        ledger.appendMissingToFile(transaction.execute(status -> ledger.tableEntries()));
        return new Result(true, fileEntriesImported, purged, toReview, revoked == null ? 0 : revoked);
    }
}
