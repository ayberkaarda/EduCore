package com.educore.ipaccess;

import com.educore.common.validation.InputPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/admin/ip-allocations}. {@code originalValue} is {@code 192.168.1.10} (STATIC),
 * {@code 192.168.1.1-192.168.1.10} (RANGE) or {@code 192.168.1.0/24} (CIDR, network address): a value of none
 * of these forms is a 400 {@code request/invalid}; a well-formed value that does not match {@code type}, a range
 * whose end lies before its start or a CIDR block with host bits set is a 400 {@code ip-allocation/invalid}.
 * The numeric bounds are always computed by the server; any {@code id}, {@code startIp} or {@code endIp} in the
 * body is ignored.
 */
public record IpAllocationRequest(@NotNull IpRangeKind type,
                                  @NotBlank @Size(max = 31) @Pattern(regexp = InputPatterns.IP_RULE_VALUE)
                                  String originalValue) {
}
