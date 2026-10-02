package com.educore.ipaccess;

import org.springframework.stereotype.Component;

import java.util.Optional;

/** Decides whether an IPv4 address may be assigned to an account: it must lie inside an IP allocation range. */
@Component
public class IpAllocationPolicy {

    private final IpAllocationRangeRepository repository;

    public IpAllocationPolicy(IpAllocationRangeRepository repository) {
        this.repository = repository;
    }

    public boolean isValidFormat(String ip) {
        return Ipv4.tryParse(ip).isPresent();
    }

    public boolean isAllocatable(String ip) {
        Optional<Ipv4> address = Ipv4.tryParse(ip);
        return address.isPresent() && repository.existsByStartIpLessThanEqualAndEndIpGreaterThanEqual(
                address.get().toLong(), address.get().toLong());
    }
}
