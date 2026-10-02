package com.educore.auth;

import com.educore.entity.Account;

/**
 * The signed-in user as returned by the auth endpoints. {@code status} is {@code ACTIVE}, or
 * {@code PENDING_DELETION} for an account in its deletion grace period (restore-only scope).
 */
public record UserView(long id, String firstName, String role, boolean mustChangePassword, String status) {

    static UserView of(Account account) {
        return new UserView(account.getId(), account.getFirstName(), account.getRole().name(),
                account.isMustChangePassword(), account.getStatus().name());
    }
}
