package com.educore.webhook;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A local HTTPS endpoint for webhook tests on 127.0.0.1 (random port) with a self-signed certificate created
 * at runtime by the JDK's {@code keytool} (SAN {@code IP:127.0.0.1}, valid two days, random store password).
 * Responses are scripted per request; every request is recorded.
 */
final class HttpsTestServer implements AutoCloseable {

    record Received(String path, Map<String, String> headers, byte[] body) {
    }

    /** One scripted answer: status, optional Location header, optional delay before answering. */
    record Reply(int status, String location, long delayMillis) {
        static Reply status(int status) {
            return new Reply(status, null, 0);
        }
    }

    private static final Tls TLS = Tls.generate();

    private final HttpsServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();

    HttpsTestServer() throws IOException {
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(TLS.serverContext()));
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    /** The server SSL context holding the test certificate (for hand-written TLS endpoints in tests). */
    static SSLContext serverContext() {
        return TLS.serverContext();
    }

    /** A client SSL context that trusts only the test certificate. */
    static SSLContext clientContext() {
        return TLS.clientContext();
    }

    String url(String path) {
        return "https://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    void reply(Reply... next) {
        replies.addAll(List.of(next));
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    void reset() {
        replies.clear();
        received.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
        Map<String, String> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.get(0)));
        received.add(new Received(exchange.getRequestURI().getPath(), headers, body));
        Reply reply = replies.poll();
        if (reply == null) {
            reply = Reply.status(200);
        }
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (reply.location() != null) {
            exchange.getResponseHeaders().add("Location", reply.location());
        }
        exchange.sendResponseHeaders(reply.status(), -1);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private record Tls(SSLContext serverContext, SSLContext clientContext) {

        static Tls generate() {
            try {
                Path dir = Files.createTempDirectory("webhook-it-tls");
                Path keystore = dir.resolve("server.p12");
                char[] password = UUID.randomUUID().toString().toCharArray();
                Path bin = Path.of(System.getProperty("java.home"), "bin");
                Path keytool = Files.exists(bin.resolve("keytool.exe")) ? bin.resolve("keytool.exe") : bin.resolve("keytool");
                Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "webhook-it",
                        "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                        "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1", "-validity", "2",
                        "-storetype", "PKCS12", "-keystore", keystore.toString(),
                        "-storepass", new String(password), "-keypass", new String(password), "-noprompt")
                        .redirectErrorStream(true).start();
                process.getInputStream().readAllBytes();
                if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    throw new IllegalStateException("keytool could not create the test certificate");
                }
                KeyStore serverStore = KeyStore.getInstance("PKCS12");
                try (InputStream in = Files.newInputStream(keystore)) {
                    serverStore.load(in, password);
                }
                KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keys.init(serverStore, password);
                SSLContext server = SSLContext.getInstance("TLS");
                server.init(keys.getKeyManagers(), null, null);

                Certificate certificate = serverStore.getCertificate("webhook-it");
                KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                trustStore.load(null, null);
                trustStore.setCertificateEntry("webhook-it", certificate);
                TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trust.init(trustStore);
                SSLContext client = SSLContext.getInstance("TLS");
                client.init(null, trust.getTrustManagers(), null);
                Files.deleteIfExists(keystore);
                Files.deleteIfExists(dir);
                return new Tls(server, client);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (Exception e) {
                throw new IllegalStateException("Test TLS setup failed", e);
            }
        }
    }
}
