package com.educore.account;

import com.educore.entity.Account;
import com.educore.entity.Role;

/** An account as returned by the admin API: no password hash, no internal flags. */
public record AccountResponse(Long id, String username, String firstName, String lastName, String studentNumber,
                              Role role, String ipAddress) {

    static AccountResponse of(Account account) {
        return new AccountResponse(account.getId(), account.getUsername(), account.getFirstName(),
                account.getLastName(), account.getStudentNumber(), account.getRole(), account.getIpAddress());
    }
}
