package com.educore.entity;

/**
 * Lifecycle state of an account ({@code account.status}, {@code V21__account_lifecycle.sql}).
 * <ul>
 *   <li>{@link #ACTIVE}: may authenticate and use every route its role allows.</li>
 *   <li>{@link #DEACTIVATED}: soft-deleted by an ADMIN; cannot authenticate; an ADMIN can restore it.</li>
 *   <li>{@link #PENDING_DELETION}: the owner requested erasure; until {@code deleteAfter} the owner may sign in
 *       with the restore-only scope; the purge job hard-deletes the account afterwards.</li>
 *   <li>{@link #DELETED}: set by the purge inside its transaction immediately before the row is removed; a
 *       committed row never keeps it.</li>
 * </ul>
 */
public enum AccountStatus {
    ACTIVE,
    DEACTIVATED,
    PENDING_DELETION,
    DELETED
}
