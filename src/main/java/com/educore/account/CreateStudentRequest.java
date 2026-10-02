package com.educore.account;

import com.educore.common.validation.InputPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/admin/accounts/students}. {@code username} is generated from the first name
 * when absent or empty; {@code ipAddress} is optional and must lie inside an IP rule; an empty
 * {@code studentNumber} means none. The new account is always a {@code USER} with a random temporary
 * password: {@code id}, {@code role}, {@code password}, {@code deleted} and {@code mustChangePassword} in the
 * body are ignored.
 */
public record CreateStudentRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = InputPatterns.PERSON_NAME) String firstName,
        @Size(max = 100) @Pattern(regexp = InputPatterns.PERSON_NAME) String lastName,
        @Pattern(regexp = InputPatterns.STUDENT_NUMBER) String studentNumber,
        @Size(max = 100) @Pattern(regexp = InputPatterns.USERNAME) String username,
        @Size(max = 15) @Pattern(regexp = InputPatterns.OPTIONAL_IPV4) String ipAddress) {
}
