package com.educore.auth;

import com.educore.common.validation.InputPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/login}. Validation errors name the field only; the submitted values
 * (password in particular) are never echoed.
 */
public record LoginRequest(
        @NotBlank @Size(max = 255) @Pattern(regexp = InputPatterns.SINGLE_LINE_TEXT) String username,
        @NotBlank @Size(max = PasswordPolicy.MAX_LENGTH) String password) {

    @Override
    public String toString() {
        return "LoginRequest[username=<omitted>, password=<omitted>]";
    }
}
