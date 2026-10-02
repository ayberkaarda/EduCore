package com.educore.ipaccess;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One range of the student IP allow-list (D-09, formerly {@code IpBlock} / table {@code ip_block}): an
 * account's assigned {@code ipAddress} must lie inside at least one range ({@link IpAllocationPolicy}).
 * {@code startIp}/{@code endIp} are the inclusive unsigned 32-bit bounds, always computed by the server.
 * This list never blocks requests; request-level blocking is {@link IpDenyRule}.
 */
@Entity
@Table(name = "ip_allocation_range")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IpAllocationRange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@link IpRangeKind} name. */
    private String type;

    /** The value as the ADMIN entered it, e.g. {@code 192.168.1.1-192.168.1.255} or {@code 10.0.0.0/24}. */
    private String originalValue;

    private Long startIp;

    private Long endIp;
}
