package com.educore.ipaccess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressTest {

    @ParameterizedTest
    @CsvSource({"198.18.6.6", "::ffff:198.18.6.6", "::FFFF:198.18.6.6", "::ffff:c612:606",
            "0:0:0:0:0:ffff:c612:606", "198.18.6.6:8080", "[::ffff:198.18.6.6]:443", " 198.18.6.6 "})
    void ipv4FormsNormaliseToTheDottedQuad(String text) {
        ClientAddress address = ClientAddress.parse(text);

        assertThat(address.isIpv4()).as(text).isTrue();
        assertThat(address.ipv4()).contains(Ipv4.parse("198.18.6.6"));
        assertThat(address.canonical()).isEqualTo("198.18.6.6");
        assertThat(address.rateLimitKey()).isEqualTo("198.18.6.6");
    }

    @Test
    void nativeIpv6IsCanonicalAndKeyedByItsSlash64() {
        ClientAddress a = ClientAddress.parse("2001:DB8:aa:bb::1");
        ClientAddress b = ClientAddress.parse("[2001:db8:aa:bb:ffff:ffff:ffff:ffff]:443");
        ClientAddress other = ClientAddress.parse("2001:db8:aa:bc::1");

        assertThat(a.isIpv6()).isTrue();
        assertThat(a.ipv4()).isEmpty();
        assertThat(a.canonical()).isEqualTo("2001:0db8:00aa:00bb:0000:0000:0000:0001");
        assertThat(a.rateLimitKey()).isEqualTo("2001:0db8:00aa:00bb::/64").isEqualTo(b.rateLimitKey());
        assertThat(other.rateLimitKey()).isNotEqualTo(a.rateLimitKey());
        assertThat(ClientAddress.parse("::1").isIpv6()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "localhost", "example.com", "1.2.3", "01.2.3.4", "1.2.3.4:0", "1.2.3.4:70000",
            "1.2.3.4:", "fe80::1%eth0", "[::1]:x", "::ffff:1.2.3.256", "1.2.3.4' OR 1=1", "unknown",
            "2001:db8:::1", ":::", "[1.2.3.4]"})
    void anythingElseIsInvalid(String text) {
        ClientAddress address = ClientAddress.parse(text);

        assertThat(address.isValid()).as(text).isFalse();
        assertThat(address.ipv4()).isEmpty();
    }

    @Test
    void nullAndOverlongInputAreInvalidAndBounded() {
        assertThat(ClientAddress.parse(null).isValid()).isFalse();
        ClientAddress longValue = ClientAddress.parse("9".repeat(1000));
        assertThat(longValue.isValid()).isFalse();
        assertThat(longValue.canonical().length()).isLessThanOrEqualTo(45);
    }
}
