package com.educore.webhook;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Apache HttpClient 5 transport for webhooks: https only, no redirects, no automatic retries, no cookies,
 * no proxy from system properties. Connect and socket timeouts come from {@code educore.webhook.connect-timeout}
 * / {@code read-timeout}; on top, every request is aborted when it has not produced a status line and headers
 * within {@code educore.webhook.request-deadline}, so an endpoint that trickles bytes cannot hold a dispatcher.
 * The response body is never read: the connection is discarded as soon as the status is known.
 * <p>
 * Before a request the host is resolved and checked against the {@link WebhookAddressPolicy}; the client's
 * {@link GuardedDnsResolver} repeats the check for the addresses it actually connects to, so an answer that
 * changes between the two lookups (DNS rebinding) is still caught.
 * <p>
 * Both lookups count against the request deadline (AC-13, R-25). Name resolution cannot be interrupted, so it
 * runs on a small bounded executor ({@value #DNS_THREADS} daemon threads, no queue) and the dispatcher waits only
 * for the time left until the deadline: a slower lookup ends the attempt as {@code timeout}, and its thread is
 * freed when the system resolver gives up. When every resolver thread is still busy the attempt fails the same
 * way at once instead of waiting.
 */
public class HttpClientWebhookTransport implements WebhookTransport, AutoCloseable {

    static final int DNS_THREADS = 4;

    private final CloseableHttpClient client;
    private final GuardedDnsResolver precheck;
    private final Duration requestDeadline;
    private final ScheduledExecutorService deadlines;
    private final ExecutorService resolvers;
    /** Deadline ({@link System#nanoTime()}) of the request the calling dispatcher thread is sending. */
    private final ThreadLocal<Long> deadlineNanos = new ThreadLocal<>();

    public HttpClientWebhookTransport(WebhookAddressPolicy policy, SSLContext sslContext, Duration connectTimeout,
                                      Duration readTimeout, Duration requestDeadline) {
        this(new GuardedDnsResolver(policy), new GuardedDnsResolver(policy), sslContext, connectTimeout, readTimeout,
                requestDeadline);
    }

    /** {@code precheck} resolves before the request, {@code connectResolver} inside the HTTP client. */
    HttpClientWebhookTransport(GuardedDnsResolver precheck, GuardedDnsResolver connectResolver, SSLContext sslContext,
                               Duration connectTimeout, Duration readTimeout, Duration requestDeadline) {
        this.precheck = precheck;
        this.requestDeadline = requestDeadline;
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "webhook-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        this.deadlines = executor;
        this.resolvers = new ThreadPoolExecutor(0, DNS_THREADS, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "webhook-dns");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        Timeout connect = Timeout.of(connectTimeout);
        Timeout read = Timeout.of(readTimeout);
        this.client = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(new DeadlineBoundResolver(connectResolver))
                        .setTlsSocketStrategy(new DefaultClientTlsStrategy(sslContext))
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(connect)
                                .setSocketTimeout(read)
                                .build())
                        .setMaxConnTotal(20)
                        .setMaxConnPerRoute(4)
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setRedirectsEnabled(false)
                        .setConnectionRequestTimeout(connect)
                        .setResponseTimeout(read)
                        .build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableContentCompression()
                .setUserAgent("EduCore-Webhooks/1")
                .build();
    }

    @Override
    public int send(URI url, Map<String, String> headers, byte[] body) throws WebhookSendException {
        if (!"https".equals(url.getScheme() == null ? null : url.getScheme().toLowerCase(Locale.ROOT))
                || url.getHost() == null) {
            throw new WebhookSendException("https-required");
        }
        long deadlineAt = System.nanoTime() + requestDeadline.toNanos();
        try {
            withinDeadline(() -> precheck.resolve(url.getHost()), deadlineAt);
        } catch (BlockedAddressException e) {
            throw new WebhookSendException("blocked-address");
        } catch (DnsTimeoutException e) {
            throw new WebhookSendException("timeout");
        } catch (UnknownHostException e) {
            throw new WebhookSendException("dns-failure");
        }
        HttpPost post = new HttpPost(url);
        headers.forEach(post::setHeader);
        post.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadlineAt - System.nanoTime()));
        ScheduledFuture<?> deadline = deadlines.schedule(post::cancel, remainingMillis, TimeUnit.MILLISECONDS);
        deadlineNanos.set(deadlineAt);
        // Closing the response without consuming the entity discards the connection; the body is never read.
        try (ClassicHttpResponse response = client.executeOpen(null, post, null)) {
            return response.getCode();
        } catch (BlockedAddressException e) {
            throw new WebhookSendException("blocked-address");
        } catch (DnsTimeoutException e) {
            throw new WebhookSendException("timeout");
        } catch (UnknownHostException e) {
            throw new WebhookSendException("dns-failure");
        } catch (SocketTimeoutException e) {
            throw new WebhookSendException("timeout");
        } catch (SSLException e) {
            throw new WebhookSendException(post.isCancelled() ? "timeout" : "tls-failure");
        } catch (IOException e) {
            throw new WebhookSendException(post.isCancelled() ? "timeout" : "connection-failure");
        } finally {
            deadline.cancel(false);
            deadlineNanos.remove();
        }
    }

    /** Visible for tests: whether the policy would let the transport connect to {@code address}. */
    boolean wouldConnectTo(InetAddress address) {
        try {
            precheck.resolve(address.getHostAddress());
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** A lookup that did not finish before the request deadline, or found every resolver thread busy. */
    static final class DnsTimeoutException extends UnknownHostException {
        DnsTimeoutException() {
            super("dns-timeout");
        }
    }

    @FunctionalInterface
    private interface Lookup<T> {
        T run() throws UnknownHostException;
    }

    /** Runs {@code lookup} on the resolver executor and waits until {@code deadlineAt} (nanoTime) at most. */
    private <T> T withinDeadline(Lookup<T> lookup, long deadlineAt) throws UnknownHostException {
        long remaining = deadlineAt - System.nanoTime();
        if (remaining <= 0) {
            throw new DnsTimeoutException();
        }
        Future<T> future;
        try {
            future = resolvers.submit(lookup::run);
        } catch (RejectedExecutionException e) {
            throw new DnsTimeoutException();
        }
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new DnsTimeoutException();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DnsTimeoutException();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownHostException unknownHost) {
                throw unknownHost;
            }
            throw new UnknownHostException("dns-failure");
        }
    }

    /** The client's resolver, bounded by the deadline of the request in progress on the calling thread. */
    private final class DeadlineBoundResolver implements DnsResolver {

        private final GuardedDnsResolver delegate;

        DeadlineBoundResolver(GuardedDnsResolver delegate) {
            this.delegate = delegate;
        }

        private long deadline() {
            Long at = deadlineNanos.get();
            return at != null ? at : System.nanoTime() + requestDeadline.toNanos();
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            return withinDeadline(() -> delegate.resolve(host), deadline());
        }

        @Override
        public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
            return withinDeadline(() -> delegate.resolve(host, port), deadline());
        }

        @Override
        public String resolveCanonicalHostname(String host) throws UnknownHostException {
            return withinDeadline(() -> delegate.resolveCanonicalHostname(host), deadline());
        }
    }

    @Override
    public void close() throws IOException {
        deadlines.shutdownNow();
        resolvers.shutdownNow();
        client.close();
    }
}
