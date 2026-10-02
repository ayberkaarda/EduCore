package com.educore.lifecycle;

import com.educore.auth.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/me/restore}: the caller re-authenticates with the current password. */
public record RestoreAccountRequest(@NotBlank @Size(max = PasswordPolicy.MAX_LENGTH) String currentPassword) {

    @Override
    public String toString() {
        return "RestoreAccountRequest[currentPassword=<omitted>]";
    }
}
