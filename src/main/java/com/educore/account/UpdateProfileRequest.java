package com.educore.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/v1/me}: the only self-editable fields. {@code id}, {@code role},
 * {@code studentNumber}, {@code ipAddress}, {@code deleted} and every other field in the body are ignored.
 */
public record UpdateProfileRequest(@NotBlank @Size(max = 100) String firstName,
                                   @NotBlank @Size(max = 100) String lastName) {
}
