package com.educore.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/auth/login}. */
public record LoginRequest(
        @NotBlank @Size(max = 255) String username,
        @NotBlank @Size(max = PasswordPolicy.MAX_LENGTH) String password) {

    @Override
    public String toString() {
        return "LoginRequest[username=<omitted>, password=<omitted>]";
    }
}
