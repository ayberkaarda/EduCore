package com.educore.webhook;

import com.educore.common.web.ApiProblemException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SSRF guard: blocked address ranges, blocked host names, URL validation at subscription time, and the
 * transport refusing a blocked target at send time before any connection is opened.
 */
class WebhookSsrfGuardTest {

    private final WebhookAddressPolicy policy = new WebhookAddressPolicy();
    private HttpClientWebhookTransport transport;

    @AfterEach
    void closeTransport() throws Exception {
        if (transport != null) {
            transport.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "127.255.255.254", "0.0.0.0", "0.1.2.3", "10.0.0.1", "10.255.255.255", "172.16.0.1",
            "172.31.255.255", "192.168.1.1", "169.254.169.254", "169.254.0.1", "100.64.0.1", "100.100.100.200",
            "192.0.0.170", "192.0.2.1", "198.18.0.1", "198.51.100.7", "203.0.113.9", "224.0.0.1", "239.255.255.250",
            "240.0.0.1", "255.255.255.255",
            "::", "::1", "fe80::1", "fc00::1", "fd00:ec2::254", "fec0::1", "ff02::1", "2001:db8::1",
            "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254", "::127.0.0.1", "64:ff9b::a9fe:a9fe"})
    void blocksLoopbackPrivateLinkLocalMetadataAndReservedAddresses(String literal) throws Exception {
        assertThat(policy.isAllowed(InetAddress.getByName(literal))).as(literal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.215.14", "8.8.8.8", "1.1.1.1", "172.32.0.1", "100.128.0.1", "2606:4700::9000000",
            "2a00:1450:4001:80b::200e", "::ffff:8.8.8.8", "64:ff9b::808:808"})
    void allowsPublicAddresses(String literal) throws Exception {
        assertThat(policy.isAllowed(InetAddress.getByName(literal))).as(literal).isTrue();
    }

    @Test
    void blocksHostNamesThatAlwaysMeanThisMachineOrCloudMetadata() {
        assertThat(policy.isBlockedHostName("localhost")).isTrue();
        assertThat(policy.isBlockedHostName("LOCALHOST.")).isTrue();
        assertThat(policy.isBlockedHostName("api.localhost")).isTrue();
        assertThat(policy.isBlockedHostName("metadata.google.internal")).isTrue();
        assertThat(policy.isBlockedHostName("hooks.example.com")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://hooks.example.com/x", "ftp://hooks.example.com/x", "https:///x", "hooks.example.com",
            "https://user:pw@hooks.example.com/x", "https://hooks.example.com/x#frag", "https://127.0.0.1/x",
            "https://[::1]/x", "https://169.254.169.254/latest/meta-data", "https://10.1.2.3:8443/x",
            "https://2130706433/x", "https://localhost/x", "https://metadata.google.internal/x", "https://a b/x"})
    void subscriptionUrlsMustBePublicHttps(String url) {
        assertThatThrownBy(() -> WebhookUrls.validate(url, policy))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        e -> assertThat(e.code()).isEqualTo("webhook/invalid-url"));
    }

    @Test
    void acceptsAPublicHttpsUrl() {
        assertThat(WebhookUrls.validate("https://hooks.example.com:8443/educore?x=1", policy))
                .isEqualTo(URI.create("https://hooks.example.com:8443/educore?x=1"));
        assertThat(WebhookUrls.validate("https://93.184.215.14/hook", policy).getHost()).isEqualTo("93.184.215.14");
    }

    @Test
    void transportRefusesABlockedTargetAtSendTimeWithoutConnecting() throws Exception {
        transport = new HttpClientWebhookTransport(policy, SSLContext.getDefault(), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(5));
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            listener.setSoTimeout(300);
            URI target = URI.create("https://127.0.0.1:" + listener.getLocalPort() + "/hook");

            assertThatThrownBy(() -> transport.send(target, Map.of(), new byte[]{'{', '}'}))
                    .isInstanceOf(WebhookTransport.WebhookSendException.class)
                    .hasMessage("blocked-address");
            assertThatThrownBy(() -> transport.send(URI.create("https://localhost:" + listener.getLocalPort() + "/"),
                    Map.of(), new byte[0])).hasMessage("blocked-address");
            assertThatThrownBy(listener::accept).isInstanceOf(SocketTimeoutException.class);
        }
        assertThat(transport.wouldConnectTo(InetAddress.getByName("169.254.169.254"))).isFalse();
        assertThat(transport.wouldConnectTo(InetAddress.getByName("8.8.8.8"))).isTrue();
    }

    @Test
    void transportSendsHttpsOnly() throws Exception {
        transport = new HttpClientWebhookTransport(policy, SSLContext.getDefault(), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(5));

        assertThatThrownBy(() -> transport.send(URI.create("http://93.184.215.14/hook"), Map.of(), new byte[0]))
                .hasMessage("https-required");
    }
}
