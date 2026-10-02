package com.educore.lifecycle;

import com.educore.auth.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code DELETE /api/v1/me}: the caller re-authenticates with the current password. */
public record DeleteAccountRequest(@NotBlank @Size(max = PasswordPolicy.MAX_LENGTH) String currentPassword) {

    @Override
    public String toString() {
        return "DeleteAccountRequest[currentPassword=<omitted>]";
    }
}
