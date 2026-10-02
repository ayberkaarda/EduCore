package com.educore.account;

import com.educore.entity.Account;
import com.educore.entity.AccountStatus;
import com.educore.entity.Role;

import java.time.Instant;

/**
 * An account as returned by the admin API: no password hash, no internal flags. {@code status} is the
 * lifecycle state; {@code deleteAfter} the end of the deletion grace period of a {@code PENDING_DELETION}
 * account ({@code null} otherwise).
 */
public record AccountResponse(Long id, String username, String firstName, String lastName, String studentNumber,
                              Role role, String ipAddress, AccountStatus status, Instant deleteAfter) {

    public static AccountResponse of(Account account) {
        return new AccountResponse(account.getId(), account.getUsername(), account.getFirstName(),
                account.getLastName(), account.getStudentNumber(), account.getRole(), account.getIpAddress(),
                account.getStatus(), account.getDeleteAfter());
    }
}
