package com.educore.ingestion;

import com.educore.config.EduCoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.integration.file.filters.FileSystemPersistentAcceptOnceFileListFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Restart safety and crash recovery.
 * <p>
 * Once at startup, before the inbox poller starts:
 * <ol>
 *   <li>{@link #recoverStagedUploads()} (also every recovery interval): only uploads whose owner's lease expired
 *       are touched; an abandoned COMMITTED upload is published into the inbox, an abandoned STAGING upload is
 *       deleted (AC-16). An upload of a live instance, this one or a sibling, is never touched;</li>
 *   <li>the accept-once marks of every file still in the inbox are released: a file that is still there was
 *       accepted by a poll but never claimed (crash or failed claim), so it must be picked up again;</li>
 *   <li>{@link #recover()}.</li>
 * </ol>
 * {@link #recover()} also runs every {@code educore.ingestion.recovery-interval} (60 s): open runs whose lease
 * expired (their instance stopped renewing it) are closed as FAILED / {@code INTERRUPTED}, their snapshots move
 * to {@code failed/} with a report, and snapshots that no live run references and that are older than two
 * lease periods are moved as well. Runs with a valid lease, of this or another instance, are never touched.
 * Dropping an interrupted file into the inbox again imports it anew (a FAILED import does not block its hash).
 * <p>
 * Only entries the protocol created are considered ({@link IngestionDirectories#leftInProcessing},
 * {@link IngestionDirectories#inInbox}, {@link IngestionDirectories#stagedTokens}): placeholders such as
 * {@code .gitkeep}, desktop metadata and other foreign files are never moved, reported or deleted.
 */
@Component
public class IngestionRecovery implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(IngestionRecovery.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UPLOAD_COMMITTED = "SELECT count(*) FROM security_event "
            + "WHERE type = 'IMPORT_UPLOADED' AND details ->> 'uploadId' = ?";

    private final IngestionDirectories directories;
    private final IngestionLedger ledger;
    private final FileSystemPersistentAcceptOnceFileListFilter acceptOnce;
    private final JdbcTemplate jdbc;
    private final UploadStaging staging;
    private final Clock clock;
    private final Duration lease;

    public IngestionRecovery(IngestionDirectories directories, IngestionLedger ledger,
                             FileSystemPersistentAcceptOnceFileListFilter ingestionAcceptOnceFilter, JdbcTemplate jdbc,
                             UploadStaging staging, EduCoreProperties properties, Clock clock) {
        this.directories = directories;
        this.ledger = ledger;
        this.acceptOnce = ingestionAcceptOnceFilter;
        this.jdbc = jdbc;
        this.staging = staging;
        this.lease = properties.ingestion().lease();
        this.clock = clock;
    }

    @Override
    public void afterSingletonsInstantiated() {
        recoverStagedUploads();
        releaseInbox();
        recover();
    }

    @Scheduled(fixedDelayString = "${educore.ingestion.recovery-interval:60s}",
            initialDelayString = "${educore.ingestion.recovery-interval:60s}")
    public void periodicRecovery() {
        recover();
        recoverStagedUploads();
    }

    /** @return the number of snapshots moved from {@code processing/} to {@code failed/} */
    public int recover() {
        Instant now = clock.instant();
        List<String> closed = ledger.failExpired(now);
        Set<String> live = ledger.liveSnapshots(now);
        Instant staleBefore = now.minus(lease.multipliedBy(2));
        int moved = 0;
        try {
            for (Path file : directories.leftInProcessing()) {
                String name = file.getFileName().toString();
                boolean ofClosedRun = closed.contains(name);
                boolean abandoned = !live.contains(name)
                        && Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(staleBefore);
                if (ofClosedRun || abandoned) {
                    Path failed = directories.finish(file, false);
                    Map<String, Object> report = new LinkedHashMap<>();
                    report.put("status", "FAILED");
                    report.put("reason", IngestionReason.INTERRUPTED.name());
                    report.put("recoveredAt", now.toString());
                    directories.writeReport(failed, JSON.writeValueAsBytes(report));
                    moved++;
                }
            }
        } catch (IOException e) {
            log.error("Interrupted imports could not be moved out of processing", e);
        }
        if (!closed.isEmpty() || moved > 0) {
            log.warn("Recovered interrupted imports jobLogsClosed={} filesMovedToFailed={}", closed.size(), moved);
        }
        return moved;
    }

    /** Releases the accept-once marks of the files still in the inbox; returns how many were released. */
    public int releaseInbox() {
        int released = 0;
        try {
            for (Path file : directories.inInbox()) {
                if (acceptOnce.remove(file.toFile())) {
                    released++;
                }
            }
        } catch (IOException e) {
            log.error("Inbox could not be listed for accept-once reconciliation", e);
        }
        if (released > 0) {
            log.warn("Released accepted but unclaimed inbox files count={}", released);
        }
        return released;
    }

    /**
     * Publishes abandoned committed uploads and deletes abandoned uncommitted ones; returns how many were
     * published. An upload is abandoned when the lease of its {@code upload_staging} row expired; the take-over is
     * a conditional delete of that row, so an owner that finishes late and this recovery never both act. A staged
     * file without a row predates the table (or lost its row): it is handled by the earlier rule (published when
     * its {@code IMPORT_UPLOADED} event committed, else deleted), but only once it is older than one lease.
     */
    public int recoverStagedUploads() {
        Instant now = clock.instant();
        int published = 0;
        try {
            for (String token : directories.stagedTokens()) {
                Optional<UploadStaging.Row> row = staging.find(token);
                if (row.isPresent()) {
                    if (row.get().leaseExpired(now) && staging.claimAbandoned(token, row.get().state(), now)) {
                        published += takeOver(token, row.get().state() == UploadStaging.State.COMMITTED);
                    }
                } else if (unregisteredAndStale(token, now)) {
                    Integer committed = jdbc.queryForObject(UPLOAD_COMMITTED, Integer.class, token);
                    published += takeOver(token, committed != null && committed > 0);
                }
            }
            // Rows of abandoned uploads whose file is already gone.
            for (String token : staging.expiredTokens(now)) {
                if (directories.stagedModifiedAt(token).isEmpty()) {
                    staging.find(token).ifPresent(row -> staging.claimAbandoned(token, row.state(), now));
                }
            }
        } catch (IOException e) {
            log.error("Staged uploads could not be recovered", e);
        }
        return published;
    }

    private int takeOver(String token, boolean committed) throws IOException {
        if (committed) {
            boolean moved = directories.publishStaged(token).isPresent();
            log.warn("Recovered an abandoned committed upload uploadId={} published={}", token, moved);
            return moved ? 1 : 0;
        }
        directories.discardStaged(token);
        log.warn("Discarded an abandoned uncommitted upload uploadId={}", token);
        return 0;
    }

    private boolean unregisteredAndStale(String token, Instant now) throws IOException {
        Optional<Instant> modified = directories.stagedModifiedAt(token);
        return modified.isPresent() && modified.get().isBefore(now.minus(lease))
                && staging.find(token).isEmpty();
    }
}
