package com.educore.lifecycle;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code educore.lifecycle.*}: account deletion, data retention and data export (P7, docs/ops/DATA_RETENTION.md).
 * <ul>
 *   <li>{@code graceDays}: days between {@code DELETE /api/v1/me} and the purge of the account (30).</li>
 *   <li>{@code purgeCron}: when {@link AccountPurgeJob} runs (Spring cron, UTC; nightly at 03:30; {@code -}
 *       disables the schedule). {@code purgeBatchSize}: accounts purged at most per run.</li>
 *   <li>{@code retentionCron}: when {@link RetentionJob} runs (UTC; nightly at 04:00; {@code -} disables it).
 *       {@code retentionDays}: age after which {@code login_attempt} (90) and {@code security_event} (365) rows
 *       are deleted.</li>
 *   <li>{@code exportPerMinute}: {@code GET /api/v1/me/export} requests per account and minute (1).
 *       {@code exportMaxSecurityEvents}: newest own security events included in an export (10 000).</li>
 *   <li>{@code erasureLedgerFile}: append-only file on a volume outside the database dump that receives one line
 *       per purged account ({@link ErasureLedger}); empty disables the file (the {@code erasure_ledger} table is
 *       always written). Required in {@code prod} ({@code EDUCORE_ERASURE_LEDGER_FILE}).</li>
 * </ul>
 */
@Validated
@ConfigurationProperties("educore.lifecycle")
public record LifecycleProperties(
        @Positive @Max(365) @DefaultValue("30") int graceDays,
        @NotBlank @DefaultValue("0 30 3 * * *") String purgeCron,
        @Positive @DefaultValue("500") int purgeBatchSize,
        @NotBlank @DefaultValue("0 0 4 * * *") String retentionCron,
        @Valid @NotNull @DefaultValue RetentionDays retentionDays,
        @Positive @DefaultValue("1") int exportPerMinute,
        @Positive @DefaultValue("10000") int exportMaxSecurityEvents,
        String erasureLedgerFile) {

    /** Whether the ledger file is configured. */
    public boolean hasErasureLedgerFile() {
        return erasureLedgerFile != null && !erasureLedgerFile.isBlank();
    }

    /** {@code educore.lifecycle.retention-days.*} */
    public record RetentionDays(
            @Positive @DefaultValue("90") int loginAttempts,
            @Positive @DefaultValue("365") int securityEvents) {
    }
}
