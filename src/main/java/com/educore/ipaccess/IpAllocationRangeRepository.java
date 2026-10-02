package com.educore.ipaccess;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IpAllocationRangeRepository extends JpaRepository<IpAllocationRange, Long> {

    /** True when some range contains {@code address} ({@code startIp <= address <= endIp}). */
    boolean existsByStartIpLessThanEqualAndEndIpGreaterThanEqual(long address, long sameAddress);
}
