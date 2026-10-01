package com.educore.security;

import com.educore.entity.Account;
import com.educore.entity.Role;

import java.util.Objects;

/**
 * The caller identified by a valid access token: account id (the token's {@code sub}), username and the
 * role currently stored for the account. This record is the security principal; method-security
 * expressions read it as {@code principal.id}, {@code principal.username} and {@code principal.role}.
 */
public record AuthenticatedUser(long id, String username, Role role) {

    public AuthenticatedUser {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(role, "role");
    }

    public static AuthenticatedUser of(Account account) {
        return new AuthenticatedUser(account.getId(), account.getUsername(), account.getRole());
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
