package com.educore.ipaccess;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/admin/ip-rules}. {@code originalValue} is {@code 192.168.1.10} (STATIC),
 * {@code 192.168.1.1-192.168.1.10} (RANGE) or {@code 192.168.1.0/24} (CIDR). The numeric bounds are always
 * computed by the server; any {@code id}, {@code startIp} or {@code endIp} in the body is ignored.
 */
public record IpRuleRequest(@NotNull IpRuleType type, @NotBlank @Size(max = 64) String originalValue) {
}
