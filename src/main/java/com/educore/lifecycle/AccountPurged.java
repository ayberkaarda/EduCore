package com.educore.lifecycle;

/** Published inside the purging transaction; consumed after commit by {@link AccountDeletedWebhookRelay}. */
public record AccountPurged(long accountId, AccountPurger.Trigger trigger) {
}
