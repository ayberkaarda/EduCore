package com.educore.account;

import com.educore.entity.Account;
import com.educore.entity.Role;

/**
 * Response of {@code POST /api/v1/admin/accounts/students}. {@code temporaryPassword} is the only place the
 * plaintext initial password ever appears: it is returned once to the ADMIN caller, stored only as a hash,
 * and the student must change it at first login. The password hash is never part of this response.
 */
public record CreatedStudentResponse(Long id, String username, String firstName, String lastName,
                                     String studentNumber, Role role, String ipAddress, String temporaryPassword) {

    static CreatedStudentResponse of(Account account, String temporaryPassword) {
        return new CreatedStudentResponse(account.getId(), account.getUsername(), account.getFirstName(),
                account.getLastName(), account.getStudentNumber(), account.getRole(), account.getIpAddress(),
                temporaryPassword);
    }

    // Records print every component; keep the temporary password out of any accidental log line.
    @Override
    public String toString() {
        return "CreatedStudentResponse[id=" + id + ", username=" + username + ", role=" + role + "]";
    }
}
