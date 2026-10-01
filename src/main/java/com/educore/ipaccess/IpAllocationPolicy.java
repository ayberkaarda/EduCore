package com.educore.ipaccess;

import com.educore.repository.IpBlockRepository;
import com.educore.util.IpAddressUtil;
import org.springframework.stereotype.Component;

/** Decides whether an IPv4 address may be assigned to an account: it must lie inside a defined IP rule. */
@Component
public class IpAllocationPolicy {

    private final IpBlockRepository ipBlockRepository;

    public IpAllocationPolicy(IpBlockRepository ipBlockRepository) {
        this.ipBlockRepository = ipBlockRepository;
    }

    public boolean isValidFormat(String ip) {
        return IpAddressUtil.isValidIpv4(ip);
    }

    public boolean isAllocatable(String ip) {
        long value = IpAddressUtil.ipToLong(ip);
        return ipBlockRepository.findAll().stream()
                .anyMatch(block -> value >= block.getStartIp() && value <= block.getEndIp());
    }
}
