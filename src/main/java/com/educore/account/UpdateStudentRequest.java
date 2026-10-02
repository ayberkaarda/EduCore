package com.educore.account;

import com.educore.common.validation.InputPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/v1/admin/accounts/{accountId}}: replaces these four fields (an empty
 * {@code studentNumber} or {@code ipAddress} removes the value). {@code id}, {@code username}, {@code role},
 * {@code password}, {@code deleted} and {@code mustChangePassword} in the body are ignored.
 */
public record UpdateStudentRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = InputPatterns.PERSON_NAME) String firstName,
        @Size(max = 100) @Pattern(regexp = InputPatterns.PERSON_NAME) String lastName,
        @Pattern(regexp = InputPatterns.STUDENT_NUMBER) String studentNumber,
        @Size(max = 15) @Pattern(regexp = InputPatterns.OPTIONAL_IPV4) String ipAddress) {
}
