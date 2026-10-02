package com.educore.ipaccess;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One textual client address (socket peer or {@code X-Forwarded-For} hop), normalised once and used by every
 * trust, deny and rate-limit decision so they always agree:
 * <ul>
 *   <li>a strict dotted quad, optionally with a {@code :port} suffix, is IPv4;</li>
 *   <li>an IPv4-mapped IPv6 address ({@code ::ffff:198.18.6.6}, {@code ::ffff:c612:606},
 *       {@code 0:0:0:0:0:ffff:c612:606}, optionally bracketed with a port) is the IPv4 address it maps;</li>
 *   <li>any other IPv6 literal (bracketed with a port allowed, zone ids not) is IPv6;</li>
 *   <li>anything else is invalid.</li>
 * </ul>
 * IPv6 literals are parsed without any name lookup: only hex digits, {@code :} and {@code .} reach
 * {@link InetAddress#getByName}, which treats such text as a literal.
 */
public final class ClientAddress {

    /** Upper bound of a textual address with brackets and port; longer input is invalid. */
    private static final int MAX_LENGTH = 64;
    private static final Pattern IPV4_WITH_PORT = Pattern.compile("([0-9.]{7,15}):([0-9]{1,5})");
    private static final Pattern BRACKETED = Pattern.compile("\\[([0-9A-Fa-f:.]+)](?::([0-9]{1,5}))?");
    private static final Pattern IPV6_TEXT = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private final Ipv4 ipv4;
    private final byte[] ipv6;
    private final String raw;

    private ClientAddress(Ipv4 ipv4, byte[] ipv6, String raw) {
        this.ipv4 = ipv4;
        this.ipv6 = ipv6;
        this.raw = raw;
    }

    public static ClientAddress parse(String text) {
        if (text == null) {
            return new ClientAddress(null, null, "unknown");
        }
        String value = text.trim();
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            return invalid(value);
        }
        Optional<Ipv4> direct = Ipv4.tryParse(value);
        if (direct.isPresent()) {
            return new ClientAddress(direct.get(), null, value);
        }
        Matcher withPort = IPV4_WITH_PORT.matcher(value);
        if (withPort.matches() && validPort(withPort.group(2))) {
            return Ipv4.tryParse(withPort.group(1)).map(a -> new ClientAddress(a, null, value))
                    .orElseGet(() -> invalid(value));
        }
        String literal = value;
        Matcher bracketed = BRACKETED.matcher(value);
        if (bracketed.matches()) {
            if (bracketed.group(2) != null && !validPort(bracketed.group(2))) {
                return invalid(value);
            }
            literal = bracketed.group(1);
        }
        if (literal.indexOf(':') < 0 || !IPV6_TEXT.matcher(literal).matches()) {
            return invalid(value);
        }
        try {
            InetAddress address = InetAddress.getByName(literal);
            if (address instanceof Inet4Address) {
                // The JDK turns IPv4-mapped IPv6 literals into the IPv4 address they map.
                return new ClientAddress(Ipv4.fromLong(Integer.toUnsignedLong(
                        java.nio.ByteBuffer.wrap(address.getAddress()).getInt())), null, value);
            }
            if (address instanceof Inet6Address) {
                return new ClientAddress(null, address.getAddress(), value);
            }
        } catch (UnknownHostException e) {
            return invalid(value);
        }
        return invalid(value);
    }

    private static ClientAddress invalid(String value) {
        return new ClientAddress(null, null, value.length() > 45 ? value.substring(0, 45) : value);
    }

    private static boolean validPort(String port) {
        int number = Integer.parseInt(port);
        return number >= 1 && number <= 65535;
    }

    /** The IPv4 address (mapped addresses included), or empty for IPv6 and invalid input. */
    public Optional<Ipv4> ipv4() {
        return Optional.ofNullable(ipv4);
    }

    public boolean isIpv4() {
        return ipv4 != null;
    }

    public boolean isIpv6() {
        return ipv6 != null;
    }

    public boolean isValid() {
        return ipv4 != null || ipv6 != null;
    }

    /**
     * The normalised text: dotted quad for IPv4 (port and mapping removed), the full lower-case
     * eight-group form for IPv6, otherwise the input (at most 45 characters).
     */
    public String canonical() {
        if (ipv4 != null) {
            return ipv4.toString();
        }
        if (ipv6 != null) {
            String hex = HexFormat.of().formatHex(ipv6);
            StringBuilder text = new StringBuilder(39);
            for (int i = 0; i < 32; i += 4) {
                text.append(i == 0 ? "" : ":").append(hex, i, i + 4);
            }
            return text.toString();
        }
        return raw;
    }

    /**
     * The rate-limit key: the IPv4 address, the IPv6 /64 network (one subscriber usually holds a whole /64,
     * so keying by full address would hand an attacker 2^64 buckets), or the raw text for invalid input.
     */
    public String rateLimitKey() {
        if (ipv6 != null) {
            String hex = HexFormat.of().formatHex(ipv6, 0, 8);
            return (hex.substring(0, 4) + ":" + hex.substring(4, 8) + ":" + hex.substring(8, 12) + ":"
                    + hex.substring(12, 16) + "::/64").toLowerCase(Locale.ROOT);
        }
        return canonical();
    }

    /**
     * The shared canonical client key of an address text ({@link #rateLimitKey()} of {@link #parse}), used by the
     * general rate limiter, the login limiter, the auto-deny failure counter and the login lockout pairs, so they
     * always agree: IPv4 exact, IPv4-mapped IPv6 as its IPv4 address, native IPv6 as its /64 network.
     */
    public static String clientKey(String text) {
        return parse(text).rateLimitKey();
    }

    @Override
    public String toString() {
        return canonical();
    }
}
