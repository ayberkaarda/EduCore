package com.educore.ipaccess;

import com.educore.common.web.ApiProblemException;
import com.educore.util.IpAddressUtil;

/** Parses an {@link IpRuleRequest} into the inclusive numeric IPv4 range it covers. */
final class IpRuleRanges {

    static final String INVALID = "ip-rule/invalid";

    record Range(long start, long end) {
    }

    private IpRuleRanges() {
    }

    static Range parse(IpRuleType type, String value) {
        String trimmed = value.trim();
        return switch (type) {
            case STATIC -> {
                requireIpv4(trimmed, "Invalid IPv4 address format.");
                long ip = IpAddressUtil.ipToLong(trimmed);
                yield new Range(ip, ip);
            }
            case RANGE -> {
                String[] parts = trimmed.split("-");
                if (parts.length != 2) {
                    throw invalid("Invalid range format (e.g. 192.168.1.1-192.168.1.10).");
                }
                requireIpv4(parts[0].trim(), "Invalid IPs inside range.");
                requireIpv4(parts[1].trim(), "Invalid IPs inside range.");
                long start = IpAddressUtil.ipToLong(parts[0].trim());
                long end = IpAddressUtil.ipToLong(parts[1].trim());
                if (end < start) {
                    throw invalid("End IP cannot be smaller than start IP.");
                }
                yield new Range(start, end);
            }
            case CIDR -> {
                String[] parts = trimmed.split("/");
                if (parts.length != 2 || !parts[1].trim().matches("[0-9]{1,2}")) {
                    throw invalid("Invalid CIDR format (e.g. 192.168.1.0/24).");
                }
                requireIpv4(parts[0].trim(), "Invalid CIDR values.");
                int prefix = Integer.parseInt(parts[1].trim());
                if (prefix > 32) {
                    throw invalid("Invalid CIDR values.");
                }
                long ip = IpAddressUtil.ipToLong(parts[0].trim());
                long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
                long start = ip & mask;
                yield new Range(start, start | (~mask & 0xFFFFFFFFL));
            }
        };
    }

    private static void requireIpv4(String ip, String title) {
        if (!IpAddressUtil.isValidIpv4(ip)) {
            throw invalid(title);
        }
    }

    private static ApiProblemException invalid(String title) {
        return ApiProblemException.badRequest(INVALID, title);
    }
}
