package com.educore.account;

import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/v1/admin/accounts/{accountId}}: replaces these four fields (a blank
 * {@code ipAddress} removes the assignment). {@code id}, {@code username}, {@code role}, {@code password},
 * {@code deleted} and {@code mustChangePassword} in the body are ignored.
 */
public record UpdateStudentRequest(@Size(max = 100) String firstName,
                                   @Size(max = 100) String lastName,
                                   @Size(max = 32) String studentNumber,
                                   @Size(max = 45) String ipAddress) {
}
