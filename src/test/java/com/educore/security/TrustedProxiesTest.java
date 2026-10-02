package com.educore.security;

import com.educore.ipaccess.Ipv4;
import com.educore.ipaccess.Ipv4Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Trusted proxy ranges, the Tomcat RemoteIpValve expression derived from them and client IP resolution. */
class TrustedProxiesTest {

    @Test
    void parsesAddressesAndCidrBlocks() {
        TrustedProxies proxies = new TrustedProxies(List.of("10.0.0.5", " 172.16.0.0/12 "));

        assertThat(proxies.ranges()).containsExactly(Ipv4Range.single(Ipv4.parse("10.0.0.5")),
                Ipv4Range.cidr("172.16.0.0/12"));
        assertThat(proxies.contains("10.0.0.5")).isTrue();
        assertThat(proxies.contains("10.0.0.6")).isFalse();
        assertThat(proxies.contains("172.31.255.255")).isTrue();
        assertThat(proxies.contains("172.32.0.0")).isFalse();
        assertThat(proxies.contains("::1")).isFalse();
        assertThat(proxies.contains((String) null)).isFalse();
        assertThat(proxies.overlaps(Ipv4Range.of("10.0.0.0", "10.0.0.5"))).isTrue();
        assertThat(proxies.overlaps(Ipv4Range.of("10.0.0.6", "10.0.0.255"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.1/8", "localhost", "::1", "010.0.0.1", "10.0.0.0/33", "", "10.0.0.0/8,"})
    void rejectsInvalidEntriesAtStartup(String entry) {
        assertThatThrownBy(() -> new TrustedProxies(List.of(entry)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("educore.ipaccess.trusted-proxies");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0/0", "0.0.0.0/1", "128.0.0.0/7", "10.0.0.0/7"})
    void rejectsBlocksBroaderThanTheMinimumPrefix(String entry) {
        assertThatThrownBy(() -> new TrustedProxies(List.of(entry)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broader than /8").hasMessageContaining("min-trusted-prefix");
    }

    @Test
    void minimumPrefixIsConfigurable() {
        assertThat(new TrustedProxies(List.of("10.0.0.0/8")).ranges()).hasSize(1);
        assertThatThrownBy(() -> new TrustedProxies(List.of("10.0.0.0/8"), 16))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("broader than /16");
        assertThat(new TrustedProxies(List.of("10.1.0.0/16", "10.2.3.4"), 16).ranges()).hasSize(2);
    }

    @Test
    void mappedIpv6PeersAreComparedAsIpv4() {
        TrustedProxies proxies = new TrustedProxies(List.of("172.30.42.10"));

        assertThat(proxies.contains("::ffff:172.30.42.10")).isTrue();
        assertThat(proxies.contains("::ffff:ac1e:2a0a")).isTrue();
        assertThat(proxies.contains("0:0:0:0:0:ffff:ac1e:2a0a")).isTrue();
        assertThat(proxies.contains("172.30.42.10:8080")).isTrue();
        assertThat(proxies.contains("::ffff:172.30.42.11")).isFalse();
        assertThat(proxies.contains("2001:db8::1")).isFalse();
    }

    @Test
    void noTrustedProxyGivesAnEmptyExpression() {
        assertThat(new TrustedProxies(List.of()).toTomcatRegex()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0/0", "10.0.0.0/8", "172.16.0.0/12", "192.168.4.0/22", "100.64.0.0/10",
            "203.0.113.8/29", "198.51.100.7", "255.255.255.254/31", "1.2.3.128/25"})
    void tomcatExpressionMatchesExactlyTheTrustedAddresses(String entry) {
        TrustedProxies proxies = new TrustedProxies(List.of(entry), 0);
        Pattern regex = Pattern.compile(proxies.toTomcatRegex());
        Ipv4Range range = proxies.ranges().get(0);
        Random random = new Random(entry.hashCode());

        for (long candidate : new long[] {range.start().toLong(), range.end().toLong(), range.start().toLong() - 1,
                range.end().toLong() + 1}) {
            if (candidate >= 0 && candidate <= 0xffff_ffffL) {
                assertMatchesIffTrusted(regex, range, Ipv4.fromLong(candidate));
            }
        }
        for (int i = 0; i < 2_000; i++) {
            long inside = range.start().toLong() + (long) (random.nextDouble() * range.size());
            assertMatchesIffTrusted(regex, range, Ipv4.fromLong(Math.min(inside, range.end().toLong())));
            assertMatchesIffTrusted(regex, range, Ipv4.fromLong(random.nextLong(0x1_0000_0000L)));
        }
    }

    @Test
    void severalEntriesAreAlternatives() {
        Pattern regex = Pattern.compile(new TrustedProxies(List.of("10.1.2.3", "192.168.0.0/16")).toTomcatRegex());

        assertThat(regex.matcher("10.1.2.3").matches()).isTrue();
        assertThat(regex.matcher("192.168.77.1").matches()).isTrue();
        assertThat(regex.matcher("10.1.2.30").matches()).isFalse();
        assertThat(regex.matcher("192.169.0.1").matches()).isFalse();
    }

    private static void assertMatchesIffTrusted(Pattern regex, Ipv4Range range, Ipv4 address) {
        assertThat(regex.matcher(address.toString()).matches()).as(address.toString())
                .isEqualTo(range.contains(address));
    }

    // ---- ClientIpResolver ---------------------------------------------------------------------------------

    private final ClientIpResolver resolver = new ClientIpResolver(new TrustedProxies(List.of("10.0.0.0/24")));

    private String resolve(String peer, String... forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        for (String header : forwardedFor) {
            if (header != null) {
                request.addHeader(ClientIpResolver.FORWARDED_FOR, header);
            }
        }
        return resolver.resolve(request);
    }

    @Test
    void repeatedHeaderLinesAreOneListInOrder() {
        assertThat(resolve("10.0.0.1", "198.51.100.1", "203.0.113.9")).isEqualTo("203.0.113.9");
        assertThat(resolve("10.0.0.1", "203.0.113.9", "198.51.100.1, 10.0.0.8")).isEqualTo("198.51.100.1");
    }

    @Test
    void mappedAddressesAndPortsAreNormalised() {
        assertThat(resolve("::ffff:10.0.0.1", "::ffff:198.51.100.1")).isEqualTo("198.51.100.1");
        assertThat(resolve("10.0.0.1", "::ffff:c633:6401")).isEqualTo("198.51.100.1");
        assertThat(resolve("10.0.0.1", "198.51.100.1:4711")).isEqualTo("198.51.100.1");
        assertThat(resolve("10.0.0.1", "[2001:db8::1]:443")).isEqualTo("2001:0db8:0000:0000:0000:0000:0000:0001");
        // A mapped trusted proxy is still a trusted proxy.
        assertThat(resolve("10.0.0.1", "198.51.100.1, ::ffff:10.0.0.7")).isEqualTo("198.51.100.1");
        assertThat(resolve("::ffff:203.0.113.9", "198.51.100.1")).isEqualTo("203.0.113.9");
    }

    @Test
    void untrustedPeerIsTheClientWhateverTheHeaderSays() {
        assertThat(resolve("203.0.113.9", "198.51.100.1")).isEqualTo("203.0.113.9");
        assertThat(resolve("203.0.113.9", (String) null)).isEqualTo("203.0.113.9");
    }

    @Test
    void trustedPeerYieldsTheRightMostUntrustedHop() {
        assertThat(resolve("10.0.0.1", "198.51.100.1")).isEqualTo("198.51.100.1");
        // The left-most value was supplied by the client; the proxy appended the real peer on the right.
        assertThat(resolve("10.0.0.1", "1.1.1.1, 198.51.100.1")).isEqualTo("198.51.100.1");
        // Inner trusted proxies are skipped.
        assertThat(resolve("10.0.0.1", "198.51.100.1, 10.0.0.7")).isEqualTo("198.51.100.1");
        assertThat(resolve("10.0.0.1", " , 198.51.100.1 ,")).isEqualTo("198.51.100.1");
    }

    @Test
    void trustedPeerWithoutForwardedClientIsTheClient() {
        assertThat(resolve("10.0.0.1", (String) null)).isEqualTo("10.0.0.1");
        assertThat(resolve("10.0.0.1", "10.0.0.7")).isEqualTo("10.0.0.1");
        assertThat(resolve("10.0.0.1", "2001:db8::1")).isEqualTo("2001:0db8:0000:0000:0000:0000:0000:0001");
    }

    @Test
    void malformedRightMostHopIsAnInvalidAddressAsInTomcat() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader(ClientIpResolver.FORWARDED_FOR, "198.51.100.1, 1.2.3.4' OR 1=1");

        assertThat(resolver.resolveAddress(request).isValid()).isFalse();
        // A malformed value left of a valid hop is client-supplied and simply ignored.
        assertThat(resolve("10.0.0.1", "not-an-ip, 198.51.100.1")).isEqualTo("198.51.100.1");
    }
}
