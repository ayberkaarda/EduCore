package com.educore.ipaccess;

import com.educore.common.validation.InputPatterns;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Body of {@code POST} and {@code PUT /api/v1/admin/ip-rules}. {@code value} is {@code 203.0.113.7}
 * (STATIC), {@code 203.0.113.10-203.0.113.20} (RANGE) or {@code 203.0.113.0/24} (CIDR, network address).
 * Characters other than hex digits, {@code .}, {@code :}, {@code /} and {@code -} are a 400
 * {@code request/invalid}; an IPv6 value is a 400 {@code ip-rule/ipv6-unsupported}; a value that does not match
 * {@code kind} is a 400 {@code ip-rule/invalid}. {@code expiresAt} (optional, ISO-8601 instant) must lie in the
 * future; absent means permanent. {@code source}, bounds, creator and timestamps are set by the server.
 */
public record IpDenyRuleRequest(
        @NotNull IpRangeKind kind,
        @NotBlank @Size(max = 43) @Pattern(regexp = "^[0-9A-Fa-f.:/\\-]+$") String value,
        @Size(max = 200) @Pattern(regexp = InputPatterns.SINGLE_LINE_TEXT) String reason,
        @Future Instant expiresAt) {
}
