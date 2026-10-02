package com.educore.ipaccess;

import java.util.Optional;

/** An IPv4 address represented as an unsigned 32-bit value. */
public record Ipv4(long value) {
    public Ipv4 {
        if (value < 0 || value > 0xffff_ffffL) {
            throw new IllegalArgumentException("IPv4 value must be between 0 and 4294967295");
        }
    }

    public static Ipv4 parse(String text) {
        if (text == null || text.isEmpty() || text.length() > 15) {
            throw new IllegalArgumentException("IPv4 must be a strict dotted quad");
        }
        long result = 0;
        int offset = 0;
        for (int part = 0; part < 4; part++) {
            int start = offset;
            int octet = 0;
            while (offset < text.length() && text.charAt(offset) != '.') {
                char digit = text.charAt(offset++);
                if (digit < '0' || digit > '9' || offset - start > 3) {
                    throw new IllegalArgumentException("IPv4 octets must contain ASCII decimal digits");
                }
                octet = octet * 10 + digit - '0';
            }
            if (offset == start || octet > 255
                    || (offset - start > 1 && text.charAt(start) == '0')) {
                throw new IllegalArgumentException("Invalid IPv4 octet");
            }
            result = (result << 8) | octet;
            if (part < 3) {
                if (offset == text.length()) {
                    throw new IllegalArgumentException("IPv4 requires exactly four octets");
                }
                offset++;
            } else if (offset != text.length()) {
                throw new IllegalArgumentException("IPv4 requires exactly four octets");
            }
        }
        return new Ipv4(result);
    }

    public static Optional<Ipv4> tryParse(String text) {
        try {
            return Optional.of(parse(text));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public long toLong() { return value; }
    public static Ipv4 fromLong(long value) { return new Ipv4(value); }

    @Override
    public String toString() {
        return (value >>> 24) + "." + ((value >>> 16) & 255) + "."
                + ((value >>> 8) & 255) + "." + (value & 255);
    }

    public boolean isLoopback() { return (value >>> 24) == 127; }
    public boolean isPrivate() {
        return (value >>> 24) == 10 || (value >>> 20) == 0xac1
                || (value >>> 16) == 0xc0a8;
    }
    public boolean isLinkLocal() { return (value >>> 16) == 0xa9fe; }
    public boolean isUnspecified() { return value == 0; }
    public boolean isMulticast() { return (value >>> 28) == 14; }
    public boolean isReserved() { return (value >>> 28) == 15; }
    public boolean isCarrierGradeNat() { return (value >>> 22) == 0x191; }
    public boolean isMetadata() { return value == 0xa9fe_a9feL; }
}
