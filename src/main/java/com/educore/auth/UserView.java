package com.educore.auth;

import com.educore.entity.Account;

/** The signed-in user as returned by the auth endpoints. */
public record UserView(long id, String firstName, String role, boolean mustChangePassword) {

    static UserView of(Account account) {
        return new UserView(account.getId(), account.getFirstName(), account.getRole().name(),
                account.isMustChangePassword());
    }
}
