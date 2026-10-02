package com.educore.ingestion;

import com.educore.security.audit.AuditService;
import com.educore.security.audit.SecurityEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Accepts a manually uploaded CSV with the limits and checks of the inbox pipeline (file name, size, text,
 * UTF-8, line breaks, header, rows; content already imported answers 409 {@code import/duplicate}).
 * <p>
 * Before the file is written to {@code staging/} (not watched), an {@code upload_staging} row owned by this
 * instance with a lease is committed ({@link UploadStaging#begin}). The upload transaction then marks it
 * COMMITTED and writes the {@code IMPORT_UPLOADED} audit event (carrying the opaque {@code uploadId}). Only after
 * the commit is the staged file moved into {@code inbox/}; a rollback deletes it. Recovery on any instance acts
 * only on uploads whose lease expired ({@link IngestionRecovery#recoverStagedUploads()}), so a restarting sibling
 * never deletes an upload that is still in flight (AC-16).
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class ImportUploadService {

    private static final Logger log = LoggerFactory.getLogger(ImportUploadService.class);

    private final CsvPreLaunchValidator validator;
    private final IngestionDirectories directories;
    private final ImportedFileRepository importedFiles;
    private final AuditService auditService;
    private final UploadStaging staging;
    private final IngestionInstance instance;
    private final Clock clock;

    public ImportUploadService(CsvPreLaunchValidator validator, IngestionDirectories directories,
                               ImportedFileRepository importedFiles, AuditService auditService, UploadStaging staging,
                               IngestionInstance instance, Clock clock) {
        this.validator = validator;
        this.directories = directories;
        this.importedFiles = importedFiles;
        this.auditService = auditService;
        this.staging = staging;
        this.instance = instance;
        this.clock = clock;
    }

    @Transactional
    public ImportAcceptedResponse accept(MultipartFile upload) {
        String safeName = FileNames.sanitize(upload.getOriginalFilename())
                .orElseThrow(() -> new IngestionRejectedException(IngestionReason.INVALID_FILE_NAME));
        if (upload.getSize() > validator.maxBytes()) {
            throw new IngestionRejectedException(IngestionReason.FILE_TOO_LARGE);
        }
        try {
            byte[] content;
            try (InputStream in = upload.getInputStream()) {
                content = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, validator.maxBytes() + 1));
            }
            ValidatedCsv csv = validator.validate(content);
            if (importedFiles.existsBySha256AndStatusNot(csv.sha256(), ImportedFile.Status.FAILED)) {
                throw new IngestionRejectedException(IngestionReason.DUPLICATE);
            }
            String token = UUID.randomUUID().toString();
            staging.begin(token, instance.id(), instance.leaseUntil(), clock.instant());
            try {
                directories.stage(token, content, safeName);
            } catch (IOException e) {
                staging.remove(token);
                throw e;
            }
            publishAfterCommit(token);
            staging.markCommitted(token, instance.id());
            auditService.recordAction(SecurityEventType.IMPORT_UPLOADED, null,
                    Map.of("kind", csv.kind().name(), "rows", csv.rows(), "size", csv.size(), "uploadId", token));
            return new ImportAcceptedResponse(IngestionDirectories.inboxNameFor(token, safeName), csv.kind(),
                    csv.rows(), csv.size(), csv.sha256());
        } catch (IOException e) {
            throw new UncheckedIOException("Uploaded CSV could not be staged", e);
        }
    }

    private void publishAfterCommit(String token) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    if (directories.publishStaged(token).isEmpty()) {
                        log.warn("Committed upload was no longer staged uploadId={}", token);
                    }
                    staging.remove(token);
                } catch (IOException | RuntimeException e) {
                    // The row stays COMMITTED: recovery publishes the file once this instance's lease expired.
                    log.error("Committed upload could not be moved into the inbox uploadId={} error={}", token,
                            e.getClass().getName());
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        directories.discardStaged(token);
                        staging.remove(token);
                    } catch (IOException | RuntimeException e) {
                        // The row stays STAGING: recovery discards the file once this instance's lease expired.
                        log.error("Rolled-back upload could not be deleted uploadId={} error={}", token,
                                e.getClass().getName());
                    }
                }
            }
        });
    }
}
