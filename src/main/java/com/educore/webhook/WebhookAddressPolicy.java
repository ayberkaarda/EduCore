package com.educore.webhook;

import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;
import java.util.Locale;

/**
 * SSRF guard: decides whether a webhook may connect to an address. Rejected:
 * <ul>
 *   <li>IPv4: {@code 0.0.0.0/8}, {@code 10.0.0.0/8}, {@code 100.64.0.0/10} (carrier-grade NAT, also
 *       Alibaba Cloud metadata {@code 100.100.100.200}), {@code 127.0.0.0/8}, {@code 169.254.0.0/16}
 *       (link-local, cloud metadata {@code 169.254.169.254}), {@code 172.16.0.0/12}, {@code 192.0.0.0/24},
 *       {@code 192.0.2.0/24}, {@code 192.168.0.0/16}, {@code 198.18.0.0/15}, {@code 198.51.100.0/24},
 *       {@code 203.0.113.0/24}, {@code 224.0.0.0/4} and {@code 240.0.0.0/4} (multicast, reserved, broadcast);</li>
 *   <li>IPv6: {@code ::}, {@code ::1}, {@code fc00::/7} (unique local, incl. AWS metadata {@code fd00:ec2::254}),
 *       {@code fe80::/10}, {@code fec0::/10}, {@code ff00::/8}, {@code 2001:db8::/32}, and IPv4-mapped,
 *       IPv4-compatible or NAT64 ({@code 64:ff9b::/96}) addresses whose embedded IPv4 address is rejected;</li>
 *   <li>host names {@code localhost}, {@code *.localhost}, {@code metadata.google.internal} and
 *       {@code metadata} (before any lookup).</li>
 * </ul>
 * The dispatcher resolves the host at send time and checks every address; the HTTP client's DNS resolver
 * checks again at connect time, so a host that resolves differently between the two lookups is still caught.
 */
@Component
public class WebhookAddressPolicy {

    private static final List<String> BLOCKED_HOST_NAMES = List.of("localhost", "metadata.google.internal",
            "metadata");

    private static final long[][] BLOCKED_IPV4 = {
            cidr(0, 0, 0, 0, 8), cidr(10, 0, 0, 0, 8), cidr(100, 64, 0, 0, 10), cidr(127, 0, 0, 0, 8),
            cidr(169, 254, 0, 0, 16), cidr(172, 16, 0, 0, 12), cidr(192, 0, 0, 0, 24), cidr(192, 0, 2, 0, 24),
            cidr(192, 168, 0, 0, 16), cidr(198, 18, 0, 0, 15), cidr(198, 51, 100, 0, 24), cidr(203, 0, 113, 0, 24),
            cidr(224, 0, 0, 0, 4), cidr(240, 0, 0, 0, 4)};

    /** {@code true} when the host name itself is never a valid webhook target. */
    public boolean isBlockedHostName(String host) {
        String name = host.toLowerCase(Locale.ROOT);
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        return BLOCKED_HOST_NAMES.contains(name) || name.endsWith(".localhost");
    }

    /** {@code true} when a webhook may connect to {@code address}. */
    public boolean isAllowed(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet4Address v4) {
            return isAllowedIpv4(v4.getAddress());
        }
        if (address instanceof Inet6Address v6) {
            return isAllowedIpv6(v6.getAddress());
        }
        return false;
    }

    private static boolean isAllowedIpv4(byte[] bytes) {
        long value = ((bytes[0] & 0xFFL) << 24) | ((bytes[1] & 0xFFL) << 16) | ((bytes[2] & 0xFFL) << 8)
                | (bytes[3] & 0xFFL);
        for (long[] range : BLOCKED_IPV4) {
            if ((value & range[1]) == range[0]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllowedIpv6(byte[] b) {
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        if ((first & 0xFE) == 0xFC) {                                   // fc00::/7 unique local
            return false;
        }
        if (first == 0xFE && (second & 0xC0) == 0x80) {                 // fe80::/10 link-local
            return false;
        }
        if (first == 0xFE && (second & 0xC0) == 0xC0) {                 // fec0::/10 site-local
            return false;
        }
        if (first == 0xFF) {                                            // ff00::/8 multicast
            return false;
        }
        if (first == 0x20 && second == 0x01 && (b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8) { // 2001:db8::/32
            return false;
        }
        boolean firstTenZero = true;
        for (int i = 0; i < 10; i++) {
            firstTenZero &= b[i] == 0;
        }
        boolean mapped = firstTenZero && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF; // ::ffff:a.b.c.d
        boolean compatible = firstTenZero && b[10] == 0 && b[11] == 0;                  // ::a.b.c.d, ::, ::1
        boolean nat64 = (first == 0x00 && second == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B); // 64:ff9b::/96
        if (mapped || compatible || nat64) {
            return isAllowedIpv4(new byte[]{b[12], b[13], b[14], b[15]});
        }
        return true;
    }

    private static long[] cidr(int a, int b, int c, int d, int prefix) {
        long network = ((long) a << 24) | ((long) b << 16) | ((long) c << 8) | d;
        long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return new long[]{network & mask, mask};
    }
}
