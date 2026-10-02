package com.educore.lifecycle;

import com.educore.auth.UsernameHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Erasure ledger (AC-09): proof of every purge that survives a database restore, without personal data.
 * <p>
 * Each purge writes one entry, in the purging transaction, to the {@code erasure_ledger} table and appends the
 * same line to {@code educore.lifecycle.erasure-ledger-file} (a volume that is not part of the database dump;
 * the line is forced to disk before the purge commits, and a failed append fails the purge):
 * <pre>
 * v1 &lt;purgedAt ISO-8601 UTC&gt; &lt;account digest&gt; &lt;username digest&gt; &lt;student number digest or -&gt;
 * </pre>
 * Digests are HMAC-SHA-256 (64 hex) under a key derived from the login pepper for this purpose only (label
 * {@value #KEY_LABEL}) over {@code account:<id>}, {@code username:<username>} and
 * {@code student-number:<number>}: nobody without the pepper can tell which account or person a line is about.
 * After a restore, {@link ErasureLedgerReplay} matches the restored accounts against the ledger and purges the
 * erased ones again. Rotating the pepper makes older lines unmatchable (docs/ops/BACKUP_RESTORE.md).
 */
@Component
public class ErasureLedger {

    static final String KEY_LABEL = "educore/erasure-ledger/v1";
    static final String VERSION = "v1";
    private static final String NONE = "-";
    private static final Pattern LINE = Pattern.compile(
            "v1 (\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z) ([0-9a-f]{64}) ([0-9a-f]{64}) ([0-9a-f]{64}|-)");

    private static final String INSERT = "INSERT INTO erasure_ledger "
            + "(account_digest, username_digest, student_number_digest, purged_at) VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (account_digest) DO NOTHING";
    private static final String ALL = "SELECT account_digest, username_digest, student_number_digest, purged_at "
            + "FROM erasure_ledger ORDER BY purged_at, account_digest";

    private static final Logger log = LoggerFactory.getLogger(ErasureLedger.class);

    /** One ledger line. {@code studentNumberDigest} is {@code null} for accounts without a student number. */
    public record Entry(String accountDigest, String usernameDigest, String studentNumberDigest, Instant purgedAt) {

        String line() {
            return VERSION + " " + purgedAt + " " + accountDigest + " " + usernameDigest + " "
                    + (studentNumberDigest == null ? NONE : studentNumberDigest);
        }

        static Optional<Entry> parse(String line) {
            Matcher matcher = LINE.matcher(line.strip());
            if (!matcher.matches()) {
                return Optional.empty();
            }
            try {
                return Optional.of(new Entry(matcher.group(2), matcher.group(3),
                        NONE.equals(matcher.group(4)) ? null : matcher.group(4), Instant.parse(matcher.group(1))));
            } catch (DateTimeParseException e) {
                return Optional.empty();
            }
        }
    }

    private final JdbcTemplate jdbc;
    private final UsernameHasher.KeyedDigest digest;
    private final Path file;

    public ErasureLedger(JdbcTemplate jdbc, UsernameHasher keyedHash, LifecycleProperties properties) {
        this.jdbc = jdbc;
        this.digest = keyedHash.derive(KEY_LABEL);
        this.file = properties.hasErasureLedgerFile() ? Path.of(properties.erasureLedgerFile().trim()) : null;
    }

    public String accountDigest(long accountId) {
        return digest.hex("account:" + accountId);
    }

    public String usernameDigest(String username) {
        return digest.hex("username:" + username);
    }

    public String studentNumberDigest(String studentNumber) {
        return studentNumber == null || studentNumber.isBlank() ? null : digest.hex("student-number:" + studentNumber);
    }

    /**
     * Records the purge of an account in the caller's (purging) transaction: table row, then the file line
     * (forced to disk). An account that is already in the ledger (a replay) is not appended again.
     *
     * @throws UncheckedIOException when the ledger file cannot be written; the purge then rolls back
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long accountId, String username, String studentNumber, Instant purgedAt) {
        Entry entry = new Entry(accountDigest(accountId), usernameDigest(username), studentNumberDigest(studentNumber),
                purgedAt);
        if (insert(entry)) {
            append(List.of(entry));
        }
    }

    /** Inserts a ledger entry unless the account is already recorded; true when a row was added. */
    boolean insert(Entry entry) {
        return jdbc.update(INSERT, entry.accountDigest(), entry.usernameDigest(), entry.studentNumberDigest(),
                Timestamp.from(entry.purgedAt())) == 1;
    }

    /** Every entry of the table. */
    List<Entry> tableEntries() {
        return jdbc.query(ALL, (rs, n) -> new Entry(rs.getString("account_digest"), rs.getString("username_digest"),
                rs.getString("student_number_digest"), rs.getTimestamp("purged_at").toInstant()));
    }

    boolean hasFile() {
        return file != null;
    }

    /** Valid entries of the ledger file (empty without a file); malformed lines are skipped and counted. */
    List<Entry> fileEntries() {
        if (file == null) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Erasure ledger file could not be read", e);
        }
        List<Entry> entries = new ArrayList<>();
        int malformed = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            Optional<Entry> entry = Entry.parse(line);
            if (entry.isPresent()) {
                entries.add(entry.get());
            } else {
                malformed++;
            }
        }
        if (malformed > 0) {
            log.warn("Erasure ledger file has malformed lines count={}", malformed);
        }
        return entries;
    }

    /** Appends table entries the file does not have yet (a new or replaced ledger volume); returns how many. */
    int appendMissingToFile(List<Entry> tableEntries) {
        if (file == null) {
            return 0;
        }
        Set<String> inFile = new HashSet<>();
        fileEntries().forEach(entry -> inFile.add(entry.accountDigest()));
        List<Entry> missing = tableEntries.stream().filter(entry -> !inFile.contains(entry.accountDigest())).toList();
        append(missing);
        return missing.size();
    }

    private synchronized void append(List<Entry> entries) {
        if (file == null || entries.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        entries.forEach(entry -> text.append(entry.line()).append('\n'));
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                channel.write(java.nio.ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.US_ASCII)));
                channel.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Erasure ledger file could not be written", e);
        }
    }
}
