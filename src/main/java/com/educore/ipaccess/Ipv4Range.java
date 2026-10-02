package com.educore.ipaccess;

/** An inclusive IPv4 interval. */
public record Ipv4Range(Ipv4 start, Ipv4 end) {
    public Ipv4Range {
        if (start == null || end == null || start.toLong() > end.toLong()) {
            throw new IllegalArgumentException("IPv4 range requires non-null start <= end");
        }
    }

    /** Parses canonical CIDR notation; bases with host bits set are rejected. */
    public static Ipv4Range cidr(String text) {
        if (text == null || text.length() > 18) {
            throw new IllegalArgumentException("Invalid IPv4 CIDR");
        }
        int slash = text.indexOf('/');
        if (slash < 1 || slash != text.lastIndexOf('/')) {
            throw new IllegalArgumentException("CIDR requires one slash and a prefix length");
        }
        Ipv4 base = Ipv4.parse(text.substring(0, slash));
        String suffix = text.substring(slash + 1);
        if (suffix.isEmpty() || suffix.length() > 2
                || (suffix.length() > 1 && suffix.charAt(0) == '0')) {
            throw new IllegalArgumentException("CIDR prefix must be a canonical decimal in 0..32");
        }
        int prefix = 0;
        for (int i = 0; i < suffix.length(); i++) {
            char digit = suffix.charAt(i);
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException("CIDR prefix must contain ASCII decimal digits");
            }
            prefix = prefix * 10 + digit - '0';
        }
        if (prefix > 32) {
            throw new IllegalArgumentException("CIDR prefix must be in 0..32");
        }
        long size = 1L << (32 - prefix);
        if (base.toLong() % size != 0) {
            throw new IllegalArgumentException("CIDR base has host bits set; use the network address");
        }
        return new Ipv4Range(base, Ipv4.fromLong(base.toLong() + size - 1));
    }

    public static Ipv4Range of(String start, String end) {
        return new Ipv4Range(Ipv4.parse(start), Ipv4.parse(end));
    }
    public static Ipv4Range single(Ipv4 address) { return new Ipv4Range(address, address); }
    public boolean contains(Ipv4 address) {
        if (address == null) { throw new IllegalArgumentException("Address must not be null"); }
        return start.toLong() <= address.toLong() && address.toLong() <= end.toLong();
    }
    public boolean overlaps(Ipv4Range other) {
        if (other == null) { throw new IllegalArgumentException("Range must not be null"); }
        return start.toLong() <= other.end.toLong() && other.start.toLong() <= end.toLong();
    }
    public long size() { return end.toLong() - start.toLong() + 1; }
    public String toCidrOrRange() {
        long count = size();
        if ((count & (count - 1)) == 0 && start.toLong() % count == 0) {
            return start + "/" + (32 - Long.numberOfTrailingZeros(count));
        }
        return start + "-" + end;
    }
}
