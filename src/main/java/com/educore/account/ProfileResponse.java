package com.educore.account;

import com.educore.entity.Account;
import com.educore.entity.Role;

/** The caller's own profile ({@code GET/PUT /api/v1/me}). */
public record ProfileResponse(Long id, String username, String firstName, String lastName, String studentNumber,
                              Role role) {

    static ProfileResponse of(Account account) {
        return new ProfileResponse(account.getId(), account.getUsername(), account.getFirstName(),
                account.getLastName(), account.getStudentNumber(), account.getRole());
    }
}
