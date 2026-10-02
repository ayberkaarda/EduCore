package com.educore.security;

import com.educore.config.EduCoreProperties;
import com.educore.ipaccess.ClientAddress;
import com.educore.ipaccess.Ipv4;
import com.educore.ipaccess.Ipv4Range;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The reverse proxies listed in {@code educore.ipaccess.trusted-proxies} (IPv4 addresses or CIDR blocks).
 * Only a direct peer inside one of these ranges may set {@code X-Forwarded-For} / {@code X-Forwarded-Proto}.
 * An entry that is neither a strict dotted-quad address nor a canonical CIDR block fails startup, and so does
 * a block broader than {@code educore.ipaccess.min-trusted-prefix} (default {@code /8}): trusting
 * {@code 0.0.0.0/0} would let every client set its own address and scheme. Addresses are compared after
 * {@link ClientAddress} normalisation, so an IPv4-mapped IPv6 peer is the IPv4 address it maps.
 */
@Component
public class TrustedProxies {

    private final List<Ipv4Range> ranges;

    /** The default of {@code educore.ipaccess.min-trusted-prefix}. */
    static final int DEFAULT_MIN_PREFIX = 8;

    @Autowired
    public TrustedProxies(EduCoreProperties properties) {
        this(properties.ipaccess().trustedProxies(), properties.ipaccess().minTrustedPrefix());
    }

    TrustedProxies(List<String> entries) {
        this(entries, DEFAULT_MIN_PREFIX);
    }

    TrustedProxies(List<String> entries, int minPrefix) {
        List<Ipv4Range> parsed = new ArrayList<>();
        for (String entry : entries) {
            Ipv4Range range = parse(entry.trim());
            if (range.size() > (1L << (32 - minPrefix))) {
                throw new IllegalStateException("educore.ipaccess.trusted-proxies entry '" + entry.trim()
                        + "' is broader than /" + minPrefix + " (educore.ipaccess.min-trusted-prefix); list the "
                        + "reverse proxy addresses or their network, never 0.0.0.0/0");
            }
            parsed.add(range);
        }
        this.ranges = List.copyOf(parsed);
    }

    private static Ipv4Range parse(String entry) {
        try {
            return entry.indexOf('/') >= 0 ? Ipv4Range.cidr(entry) : Ipv4Range.single(Ipv4.parse(entry));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("educore.ipaccess.trusted-proxies contains an entry that is not an IPv4 "
                    + "address or canonical IPv4 CIDR block: '" + entry + "'", e);
        }
    }

    /** The configured ranges, in configuration order. */
    public List<Ipv4Range> ranges() {
        return ranges;
    }

    public boolean contains(Ipv4 address) {
        for (Ipv4Range range : ranges) {
            if (range.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code address} is (or maps) an IPv4 address inside a trusted range. */
    public boolean contains(String address) {
        return contains(ClientAddress.parse(address));
    }

    public boolean contains(ClientAddress address) {
        return address.ipv4().map(this::contains).orElse(false);
    }

    /** True when {@code range} shares at least one address with a trusted proxy range. */
    public boolean overlaps(Ipv4Range range) {
        for (Ipv4Range trusted : ranges) {
            if (trusted.overlaps(range)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A regular expression that fully matches exactly the textual (dotted-quad) addresses inside the trusted
     * ranges, for Tomcat's {@code RemoteIpValve#setInternalProxies}; empty when no proxy is trusted (the valve
     * then trusts no peer).
     */
    public String toTomcatRegex() {
        List<String> alternatives = new ArrayList<>();
        for (Ipv4Range range : ranges) {
            alternatives.addAll(regexesFor(range));
        }
        return String.join("|", alternatives);
    }

    /** Splits {@code range} into CIDR blocks and converts each into an octet-wise expression. */
    private static List<String> regexesFor(Ipv4Range range) {
        List<String> result = new ArrayList<>();
        long start = range.start().toLong();
        long end = range.end().toLong();
        while (start <= end) {
            int hostBits = Math.min(Long.numberOfTrailingZeros(start == 0 ? 1L << 32 : start), 32);
            while (hostBits > 0 && start + (1L << hostBits) - 1 > end) {
                hostBits--;
            }
            result.add(cidrRegex(start, 32 - hostBits));
            start += 1L << hostBits;
        }
        return result;
    }

    private static String cidrRegex(long base, int prefix) {
        StringBuilder regex = new StringBuilder();
        for (int octet = 0; octet < 4; octet++) {
            if (octet > 0) {
                regex.append("\\.");
            }
            int value = (int) ((base >>> (24 - 8 * octet)) & 0xff);
            int fixedBits = Math.max(0, Math.min(8, prefix - 8 * octet));
            if (fixedBits == 8) {
                regex.append(value);
            } else if (fixedBits == 0) {
                regex.append("\\d{1,3}");
            } else {
                int count = 1 << (8 - fixedBits);
                regex.append("(?:");
                for (int i = 0; i < count; i++) {
                    regex.append(i == 0 ? "" : "|").append(value + i);
                }
                regex.append(')');
            }
        }
        return regex.toString();
    }
}
