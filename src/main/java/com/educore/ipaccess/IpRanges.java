package com.educore.ipaccess;

/**
 * Parses the textual forms of {@link IpRangeKind}: {@code 192.168.1.10} (STATIC),
 * {@code 192.168.1.1-192.168.1.10} (RANGE, inclusive, start not after end) and {@code 192.168.1.0/24} (CIDR,
 * canonical network address, prefix 0-32). Addresses are strict dotted quads ({@link Ipv4#parse}).
 */
final class IpRanges {

    private IpRanges() {
    }

    /** @throws IllegalArgumentException when {@code value} is not a valid value of {@code kind} */
    static Ipv4Range parse(IpRangeKind kind, String value) {
        if (kind == null || value == null) {
            throw new IllegalArgumentException("Kind and value are required");
        }
        String trimmed = value.trim();
        return switch (kind) {
            case STATIC -> Ipv4Range.single(Ipv4.parse(trimmed));
            case RANGE -> {
                int dash = trimmed.indexOf('-');
                if (dash < 0 || dash != trimmed.lastIndexOf('-')) {
                    throw new IllegalArgumentException("A range is written start-end");
                }
                yield Ipv4Range.of(trimmed.substring(0, dash).trim(), trimmed.substring(dash + 1).trim());
            }
            case CIDR -> Ipv4Range.cidr(trimmed);
        };
    }

    /** The canonical text of {@code range} written as {@code kind}. */
    static String format(IpRangeKind kind, Ipv4Range range) {
        return switch (kind) {
            case STATIC -> range.start().toString();
            case RANGE -> range.start() + "-" + range.end();
            case CIDR -> range.toCidrOrRange();
        };
    }

    /** True when the value is written as IPv6 (contains a colon); IPv6 is not supported. */
    static boolean isIpv6(String value) {
        return value != null && value.indexOf(':') >= 0;
    }
}
