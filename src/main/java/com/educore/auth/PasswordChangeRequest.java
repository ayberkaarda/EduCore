package com.educore.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/password}. Length limits of the new password are reported by
 * {@link PasswordPolicy} so the client receives every violation at once.
 */
public record PasswordChangeRequest(
        @NotBlank @Size(max = PasswordPolicy.MAX_LENGTH) String currentPassword,
        @NotBlank @Size(max = 1024) String newPassword) {

    @Override
    public String toString() {
        return "PasswordChangeRequest[currentPassword=<omitted>, newPassword=<omitted>]";
    }
}
