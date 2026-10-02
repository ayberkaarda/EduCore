package com.educore.ingestion;

import com.educore.config.EduCoreProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Identity of this application instance as the owner of import runs and staged uploads, and its heartbeat:
 * every {@code educore.ingestion.heartbeat-interval} (30 s) the leases of the runs and uploads it has open are
 * extended by {@code educore.ingestion.lease} (2 min). A lease that already expired is not revived. A run or
 * upload whose lease expired belongs to an instance that stopped (or stalled); any instance's recovery may then
 * close it, and the stalled owner's later writes are rejected ({@link IngestionFence},
 * {@link UploadStaging#markCommitted}).
 */
@Component
public class IngestionInstance {

    private final String id = "instance-" + UUID.randomUUID();
    private final Duration lease;
    private final IngestionLedger ledger;
    private final UploadStaging uploads;
    private final Clock clock;

    public IngestionInstance(EduCoreProperties properties, IngestionLedger ledger, UploadStaging uploads,
                             Clock clock) {
        this.lease = properties.ingestion().lease();
        this.ledger = ledger;
        this.uploads = uploads;
        this.clock = clock;
    }

    public String id() {
        return id;
    }

    /** The lease end for a run or upload opened or renewed now. */
    public Instant leaseUntil() {
        return clock.instant().plus(lease);
    }

    @Scheduled(fixedDelayString = "${educore.ingestion.heartbeat-interval:30s}")
    public void heartbeat() {
        Instant now = clock.instant();
        Instant until = now.plus(lease);
        ledger.renewLeases(id, now, until);
        uploads.renewLeases(id, now, until);
    }
}
