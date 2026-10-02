package com.educore.ipaccess;

import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ipv4Test {
    @ParameterizedTest
    @CsvSource({"0.0.0.0,0", "255.255.255.255,4294967295", "127.0.0.1,2130706433",
            "128.0.0.0,2147483648", "1.2.3.4,16909060", "192.168.1.255,3232236031"})
    void canonicalRoundTrip(String text, long value) {
        Ipv4 address = Ipv4.parse(text);
        assertThat(address.toLong()).isEqualTo(value);
        assertThat(address.toString()).isEqualTo(text);
        assertThat(Ipv4.fromLong(value)).isEqualTo(address);
        assertThat(Ipv4.tryParse(text)).contains(address);
    }

    static Stream<String> invalidAddresses() {
        return Stream.of("256.0.0.0", "0.256.0.0", "0.0.256.0", "0.0.0.256",
                "01.2.3.4", "1.00.3.4", "1.2.010.4", "1.2.3.01", "00.0.0.0",
                "127.1", "1.2.3", "1.2.3.4.5", "1..3.4", ".1.2.3", "1.2.3.4.",
                "1.2.3.", "0x7f.0.0.1", "2130706433", "::1", "::ffff:1.2.3.4",
                "+1.2.3.4", "1.2.-3.4", " 1.2.3.4", "1.2.3.4 ", "1. 2.3.4",
                "1.2.3.4\n", "1.2.3.4\r", "1.2.3.4\t", "1.\t2.3.4",
                "\u0661.2.3.4", "1.2.3.\uff14", "1.2.3.4, 5.6.7.8", "1.2.3.4:80",
                "1.2.3.4\u0000", "1".repeat(10000));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @MethodSource("invalidAddresses")
    void rejectsMalformedAddresses(String text) {
        assertThatThrownBy(() -> Ipv4.parse(text)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Ipv4.tryParse(text)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, Long.MIN_VALUE, 4294967296L, Long.MAX_VALUE})
    void rejectsOutOfRangeValues(long value) {
        assertThatThrownBy(() -> Ipv4.fromLong(value)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Ipv4(value)).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> classifications() {
        return Stream.of(
            block(Ipv4::isLoopback, "126.255.255.255", "127.0.0.0", "127.255.255.255", "128.0.0.0"),
            block(Ipv4::isPrivate, "9.255.255.255", "10.0.0.0", "10.255.255.255", "11.0.0.0"),
            block(Ipv4::isPrivate, "172.15.255.255", "172.16.0.0", "172.31.255.255", "172.32.0.0"),
            block(Ipv4::isPrivate, "192.167.255.255", "192.168.0.0", "192.168.255.255", "192.169.0.0"),
            block(Ipv4::isLinkLocal, "169.253.255.255", "169.254.0.0", "169.254.255.255", "169.255.0.0"),
            block(Ipv4::isMulticast, "223.255.255.255", "224.0.0.0", "239.255.255.255", "240.0.0.0"),
            block(Ipv4::isCarrierGradeNat, "100.63.255.255", "100.64.0.0", "100.127.255.255", "100.128.0.0"),
            block(Ipv4::isMetadata, "169.254.169.253", "169.254.169.254", "169.254.169.254", "169.254.169.255"),
            Stream.of(Arguments.of((Predicate<Ipv4>) Ipv4::isReserved, "239.255.255.255", false),
                Arguments.of((Predicate<Ipv4>) Ipv4::isReserved, "240.0.0.0", true),
                Arguments.of((Predicate<Ipv4>) Ipv4::isReserved, "255.255.255.255", true),
                Arguments.of((Predicate<Ipv4>) Ipv4::isUnspecified, "0.0.0.0", true),
                Arguments.of((Predicate<Ipv4>) Ipv4::isUnspecified, "0.0.0.1", false),
                Arguments.of((Predicate<Ipv4>) Ipv4::isUnspecified, "255.255.255.255", false)))
            .flatMap(stream -> stream);
    }

    private static Stream<Arguments> block(Predicate<Ipv4> predicate, String before,
            String first, String last, String after) {
        return Stream.of(Arguments.of(predicate, before, false), Arguments.of(predicate, first, true),
                Arguments.of(predicate, last, true), Arguments.of(predicate, after, false));
    }

    @ParameterizedTest
    @MethodSource("classifications")
    void classificationBoundaries(Predicate<Ipv4> predicate, String text, boolean expected) {
        assertThat(predicate.test(Ipv4.parse(text))).isEqualTo(expected);
    }

    @Test
    void everyOctetValueRoundTripsInEveryPosition() {
        for (int octet = 0; octet <= 255; octet++) {
            for (int shift = 0; shift <= 24; shift += 8) {
                long value = (long) octet << shift;
                Ipv4 address = Ipv4.fromLong(value);
                assertThat(Ipv4.parse(address.toString())).isEqualTo(address);
                assertThat(Ipv4.tryParse(address.toString())).contains(address);
            }
        }
    }
}
