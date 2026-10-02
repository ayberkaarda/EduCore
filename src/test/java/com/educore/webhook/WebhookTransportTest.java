package com.educore.webhook;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLServerSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real HTTPS transport against hand-written TLS endpoints on 127.0.0.1: a server trickling its headers is
 * cut off by the overall request deadline, a trickling body is never read, and a host whose DNS answer turns
 * private between the pre-check and the connection is refused at connect time.
 */
class WebhookTransportTest {

    /** Lets the test reach its own loopback endpoint; every other address follows the production policy. */
    private static final WebhookAddressPolicy LOOPBACK_ALLOWED = new WebhookAddressPolicy() {
        @Override
        public boolean isAllowed(InetAddress address) {
            return address.isLoopbackAddress() || super.isAllowed(address);
        }
    };

    private SSLServerSocket server;
    private Thread serverThread;
    private HttpClientWebhookTransport transport;

    @AfterEach
    void stop() throws Exception {
        if (transport != null) {
            transport.close();
        }
        if (server != null) {
            server.close();
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    /** Accepts connections and writes {@code head} then {@code trickle} one byte every 300 ms. */
    private int start(String head, String trickle) throws IOException {
        server = (SSLServerSocket) HttpsTestServer.serverContext().getServerSocketFactory()
                .createServerSocket(0, 10, InetAddress.getLoopbackAddress());
        serverThread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    InputStream in = socket.getInputStream();
                    byte[] request = new byte[8192];
                    int read = in.read(request);
                    if (read <= 0) {
                        continue;
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write(head.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    for (int i = 0; i < 200 && !socket.isClosed(); i++) {
                        out.write(trickle.charAt(i % trickle.length()));
                        out.flush();
                        Thread.sleep(300);
                    }
                } catch (IOException e) {
                    // Client went away (expected when the transport aborts).
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "trickle-server");
        serverThread.setDaemon(true);
        serverThread.start();
        return server.getLocalPort();
    }

    private HttpClientWebhookTransport transport(Duration readTimeout, Duration deadline) {
        return new HttpClientWebhookTransport(LOOPBACK_ALLOWED, HttpsTestServer.clientContext(), Duration.ofSeconds(2),
                readTimeout, deadline);
    }

    @Test
    void headersTrickledSlowerThanTheDeadlineAbortTheRequest() throws Exception {
        int port = start("HTTP/1.1 200 OK\r\n", "X-Slow: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\r\n");
        // Each byte arrives well within the 1 s socket timeout, so only the overall deadline can stop it.
        transport = transport(Duration.ofSeconds(1), Duration.ofSeconds(2));

        long started = System.nanoTime();
        assertThatThrownBy(() -> transport.send(URI.create("https://127.0.0.1:" + port + "/hook"), Map.of(),
                "{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(WebhookTransport.WebhookSendException.class)
                .hasMessage("timeout");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(6));
    }

    @Test
    void theResponseBodyIsNeverRead() throws Exception {
        int port = start("HTTP/1.1 204 No Content\r\nContent-Length: 100000\r\n\r\n", "x");
        transport = transport(Duration.ofSeconds(5), Duration.ofSeconds(10));

        long started = System.nanoTime();
        int status = transport.send(URI.create("https://127.0.0.1:" + port + "/hook"), Map.of(), new byte[]{'{', '}'});

        assertThat(status).isEqualTo(204);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void aDnsAnswerThatTurnsPrivateBetweenCheckAndConnectIsRefused() throws Exception {
        int port = start("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n", "x");
        AtomicInteger lookups = new AtomicInteger();
        // First answer public (passes the pre-check), every later answer the loopback endpoint.
        GuardedDnsResolver.Lookup rebinding = host -> lookups.getAndIncrement() == 0
                ? new InetAddress[]{InetAddress.getByName("93.184.215.14")}
                : new InetAddress[]{InetAddress.getLoopbackAddress()};
        WebhookAddressPolicy production = new WebhookAddressPolicy();
        transport = new HttpClientWebhookTransport(new GuardedDnsResolver(production, rebinding),
                new GuardedDnsResolver(production, rebinding), HttpsTestServer.clientContext(), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(5));

        assertThatThrownBy(() -> transport.send(URI.create("https://hooks.rebind.example:" + port + "/hook"), Map.of(),
                new byte[]{'{', '}'}))
                .isInstanceOf(WebhookTransport.WebhookSendException.class)
                .hasMessage("blocked-address");
        assertThat(lookups.get()).isGreaterThanOrEqualTo(2);
    }

    /**
     * R-25: a resolver that never answers (slow authoritative server) must not hold the dispatcher beyond the
     * request deadline. Before the fix the pre-check lookup ran before the deadline was armed and blocked for as
     * long as the resolver did.
     */
    @Test
    void aBlockingPreCheckLookupEndsAsTimeoutWithinTheDeadline() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        GuardedDnsResolver.Lookup blocking = host -> {
            awaitQuietly(never);
            throw new java.net.UnknownHostException(host);
        };
        WebhookAddressPolicy production = new WebhookAddressPolicy();
        transport = new HttpClientWebhookTransport(new GuardedDnsResolver(production, blocking),
                new GuardedDnsResolver(production, blocking), HttpsTestServer.clientContext(), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofMillis(800));
        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> transport.send(URI.create("https://slow-dns.example/hook"), Map.of(),
                    new byte[]{'{', '}'}))
                    .isInstanceOf(WebhookTransport.WebhookSendException.class)
                    .hasMessage("timeout");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        } finally {
            never.countDown();
        }
    }

    /** The connect-time lookup (the rebinding re-check) counts against the same deadline. */
    @Test
    void aBlockingConnectTimeLookupEndsAsTimeoutWithinTheDeadline() throws Exception {
        int port = start("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n", "x");
        CountDownLatch never = new CountDownLatch(1);
        GuardedDnsResolver.Lookup fast = host -> new InetAddress[]{InetAddress.getLoopbackAddress()};
        GuardedDnsResolver.Lookup blocking = host -> {
            awaitQuietly(never);
            throw new java.net.UnknownHostException(host);
        };
        transport = new HttpClientWebhookTransport(new GuardedDnsResolver(LOOPBACK_ALLOWED, fast),
                new GuardedDnsResolver(LOOPBACK_ALLOWED, blocking), HttpsTestServer.clientContext(),
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofMillis(800));
        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> transport.send(URI.create("https://hooks.slow.example:" + port + "/hook"),
                    Map.of(), new byte[]{'{', '}'}))
                    .isInstanceOf(WebhookTransport.WebhookSendException.class)
                    .hasMessage("timeout");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        } finally {
            never.countDown();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
