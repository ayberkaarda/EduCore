package com.educore.security;

import com.educore.entity.Account;

/**
 * The single definition of an account that may authenticate: it exists, has a role and is not soft-deleted
 * ({@code deleted == 0}). Login, refresh and bearer-token authentication all use it.
 */
public final class ActiveAccount {

    private ActiveAccount() {
    }

    public static boolean isActive(Account account) {
        return account != null
                && account.getRole() != null
                && account.getDeleted() != null
                && account.getDeleted() == 0;
    }
}
