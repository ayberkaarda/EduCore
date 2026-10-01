package com.educore.account;

import com.educore.entity.Role;
import jakarta.validation.constraints.NotNull;

/** Body of {@code PUT /api/v1/admin/accounts/{accountId}/role}; an unknown role value is a 400. */
public record ChangeRoleRequest(@NotNull Role role) {
}
