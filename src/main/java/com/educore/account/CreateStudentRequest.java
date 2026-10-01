package com.educore.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/admin/accounts/students}. {@code username} is generated from the first name
 * when absent; {@code ipAddress} is optional and must lie inside an IP rule. The new account is always a
 * {@code USER} with a random temporary password: {@code id}, {@code role}, {@code password}, {@code deleted}
 * and {@code mustChangePassword} in the body are ignored.
 */
public record CreateStudentRequest(@NotBlank @Size(max = 100) String firstName,
                                   @Size(max = 100) String lastName,
                                   @Size(max = 32) String studentNumber,
                                   @Size(max = 100) String username,
                                   @Size(max = 45) String ipAddress) {
}
