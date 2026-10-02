package com.educore.security;

import com.educore.entity.Account;
import com.educore.entity.AccountStatus;

import java.time.Instant;

/**
 * The single definition of which accounts may authenticate, used by login, refresh and bearer-token
 * authentication.
 * <ul>
 *   <li>{@link #isActive}: the account exists, has a role and its status is {@link AccountStatus#ACTIVE}. Only
 *       such accounts get full access.</li>
 *   <li>{@link #inGracePeriod}: the account requested its own deletion ({@link AccountStatus#PENDING_DELETION})
 *       and {@code deleteAfter} has not passed. It may sign in, but only with the restore-only scope
 *       ({@code PendingDeletionScopeFilter}).</li>
 *   <li>Every other account ({@code DEACTIVATED}, {@code DELETED}, a pending account after its grace period,
 *       no role) cannot authenticate at all.</li>
 * </ul>
 */
public final class ActiveAccount {

    private ActiveAccount() {
    }

    public static boolean isActive(Account account) {
        return account != null
                && account.getRole() != null
                && account.getStatus() == AccountStatus.ACTIVE;
    }

    /** A {@code PENDING_DELETION} account whose grace period has not ended at {@code now}. */
    public static boolean inGracePeriod(Account account, Instant now) {
        return account != null
                && account.getRole() != null
                && account.getStatus() == AccountStatus.PENDING_DELETION
                && account.getDeleteAfter() != null
                && now.isBefore(account.getDeleteAfter());
    }

    /** Whether the account may authenticate at {@code now}: active, or pending deletion within the grace period. */
    public static boolean mayAuthenticate(Account account, Instant now) {
        return isActive(account) || inGracePeriod(account, now);
    }
}
