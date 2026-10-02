package com.educore.lifecycle;

import com.educore.webhook.WebhookEvent;
import com.educore.webhook.WebhookPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Queues the {@code account.deleted} webhook for a purged account through the existing {@link WebhookPublisher},
 * after the purging transaction committed (a purge that rolls back produces no event), in a transaction of its
 * own. Data: {@code {accountId, mode: HARD}} (the surrogate id only, as for {@code mode: SOFT}; no personal
 * data). A failure to queue is logged and does not undo the purge.
 */
@Component
public class AccountDeletedWebhookRelay {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletedWebhookRelay.class);

    private final WebhookPublisher publisher;

    public AccountDeletedWebhookRelay(WebhookPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPurged(AccountPurged purged) {
        try {
            publisher.publish(WebhookEvent.ACCOUNT_DELETED, Map.of("accountId", purged.accountId(), "mode", "HARD"));
        } catch (RuntimeException e) {
            log.error("Webhook event could not be queued event={}", WebhookEvent.ACCOUNT_DELETED.value(), e);
        }
    }
}
