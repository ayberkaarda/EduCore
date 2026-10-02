package com.educore.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/admin/accounts/{accountId}/purge}: {@code confirm} must equal the username of the
 * account. It travels in the body so that it never appears in a request line (access logs).
 */
public record PurgeAccountRequest(@NotBlank @Size(max = 255) String confirm) {

    @Override
    public String toString() {
        return "PurgeAccountRequest[confirm=<omitted>]";
    }
}
