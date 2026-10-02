package com.educore.lifecycle;

import com.educore.entity.AccountStatus;

import java.time.Instant;

/**
 * Answer of {@code DELETE /api/v1/me} (202): the account is {@code PENDING_DELETION} and is purged after
 * {@code deleteAfter} unless restored with {@code POST /api/v1/me/restore} before then.
 */
public record DeletionScheduledResponse(AccountStatus status, Instant deleteAfter) {
}
