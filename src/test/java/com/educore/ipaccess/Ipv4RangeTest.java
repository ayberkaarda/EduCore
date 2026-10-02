package com.educore.ipaccess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ipv4RangeTest {
    @ParameterizedTest
    @CsvSource({"0.0.0.0/0,255.255.255.255,4294967296", "0.0.0.0/1,127.255.255.255,2147483648",
        "128.0.0.0/1,255.255.255.255,2147483648", "10.0.0.0/8,10.255.255.255,16777216",
        "192.168.0.0/16,192.168.255.255,65536", "192.168.1.0/24,192.168.1.255,256",
        "255.255.255.254/31,255.255.255.255,2", "255.255.255.255/32,255.255.255.255,1",
        "0.0.0.0/32,0.0.0.0,1"})
    void parsesCanonicalCidr(String text, String last, long size) {
        Ipv4Range range = Ipv4Range.cidr(text);
        assertThat(range.start()).isEqualTo(Ipv4.parse(text.substring(0, text.indexOf('/'))));
        assertThat(range.end()).isEqualTo(Ipv4.parse(last));
        assertThat(range.size()).isEqualTo(size);
        assertThat(range.toCidrOrRange()).isEqualTo(text);
        assertThat(range.contains(range.start())).isTrue();
        assertThat(range.contains(range.end())).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1.2.3.4/33", "1.2.3.4/-1", "1.2.3.4/", "10.0.0.0/08",
        "10.0.0.0/ 8", "10.0.0.0/+8", "10.0.0.0/8\n", "10.0.0.0/8/8", "10.0.0.0",
        "/8", "10.0.0.5/8", "1.0.0.0/0", "128.0.0.1/1", "192.168.1.1/24",
        "255.255.255.255/31", "01.0.0.0/8", "256.0.0.0/8", "::1/32",
        "10.0.0.0/\u0668", "10.0.0.0/\uff18", "10.0.0.0/000", "10.0.0.0/99"})
    void rejectsInvalidCidr(String text) {
        assertThatThrownBy(() -> Ipv4Range.cidr(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsHostBitsWithClearMessage() {
        assertThatThrownBy(() -> Ipv4Range.cidr("10.0.0.5/8"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("host bits");
    }

    @ParameterizedTest
    @CsvSource({"0.0.0.9,false", "0.0.0.10,true", "0.0.0.15,true", "0.0.0.20,true", "0.0.0.21,false"})
    void containsInclusiveBoundaries(String address, boolean expected) {
        assertThat(Ipv4Range.of("0.0.0.10", "0.0.0.20").contains(Ipv4.parse(address))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"0.0.0.0,0.0.0.9,false", "0.0.0.0,0.0.0.10,true",
        "0.0.0.20,0.0.0.30,true", "0.0.0.21,0.0.0.30,false",
        "0.0.0.12,0.0.0.18,true", "0.0.0.0,255.255.255.255,true",
        "0.0.0.10,0.0.0.20,true", "0.0.0.15,0.0.0.15,true"})
    void overlapsSymmetrically(String start, String end, boolean expected) {
        Ipv4Range range = Ipv4Range.of("0.0.0.10", "0.0.0.20");
        Ipv4Range other = Ipv4Range.of(start, end);
        assertThat(range.overlaps(other)).isEqualTo(expected);
        assertThat(other.overlaps(range)).isEqualTo(expected);
    }

    @Test
    void validatesConstructionAndNullArguments() {
        Ipv4 zero = Ipv4.fromLong(0);
        Ipv4 one = Ipv4.fromLong(1);
        assertThatThrownBy(() -> new Ipv4Range(one, zero)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Ipv4Range(null, zero)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Ipv4Range(zero, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ipv4Range.single(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ipv4Range.of(null, "0.0.0.0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ipv4Range.of("0.0.0.0", "bad")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ipv4Range.single(zero).contains(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ipv4Range.single(zero).overlaps(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "128.0.0.0", "255.255.255.255"})
    void singleAddressRange(String text) {
        Ipv4 address = Ipv4.parse(text);
        Ipv4Range range = Ipv4Range.single(address);
        assertThat(range).isEqualTo(new Ipv4Range(address, address));
        assertThat(range.size()).isEqualTo(1);
        assertThat(range.contains(address)).isTrue();
        assertThat(range.toCidrOrRange()).isEqualTo(text + "/32");
    }

    @ParameterizedTest
    @CsvSource({"0.0.0.1,0.0.0.2,0.0.0.1-0.0.0.2", "0.0.0.0,0.0.0.2,0.0.0.0-0.0.0.2",
        "255.255.255.253,255.255.255.255,255.255.255.253-255.255.255.255"})
    void formatsNonCidrIntervals(String start, String end, String expected) {
        assertThat(Ipv4Range.of(start, end).toCidrOrRange()).isEqualTo(expected);
    }

    @Test
    void everyPrefixRoundTripsAtBothEndsOfAddressSpace() {
        for (int prefix = 0; prefix <= 32; prefix++) {
            long size = 1L << (32 - prefix);
            for (long base : new long[] {0, 4294967296L - size}) {
                String text = Ipv4.fromLong(base) + "/" + prefix;
                Ipv4Range range = Ipv4Range.cidr(text);
                assertThat(range.size()).isEqualTo(size);
                assertThat(range.toCidrOrRange()).isEqualTo(text);
                if (base > 0) assertThat(range.contains(Ipv4.fromLong(base - 1))).isFalse();
                if (base + size < 4294967296L) assertThat(range.contains(Ipv4.fromLong(base + size))).isFalse();
            }
        }
    }
}
