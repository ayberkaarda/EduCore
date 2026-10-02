package com.educore.web;

import com.educore.entity.Account;
import com.educore.entity.Role;
import com.educore.repository.AccountRepository;
import com.educore.security.AuthenticatedUser;
import com.educore.security.JwtService;
import com.educore.weather.WeatherService;
import com.educore.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Every error path answers an RFC 9457 problem in one shape ({@code application/problem+json},
 * {@code type/title/status/detail/instance/code}, plus {@code errors} for 400 and {@code correlationId} for
 * 5xx) with {@code X-Content-Type-Options: nosniff}, and never leaks messages, stack traces or class names.
 * Requests go through a real Tomcat (multipart limits, the error dispatch and the firewall only exist there);
 * the login throttling paths use MockMvc to control the client address.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.servlet.multipart.max-file-size=1KB", "spring.servlet.multipart.max-request-size=1KB"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ProblemDetailsIT extends AbstractIntegrationTest {

    /** TEST DATA ONLY: password of the accounts created here. */
    private static final String PASSWORD = "problem-details-test-only-value";
    private static final String LEAK = "SELECT password FROM account WHERE com.educore.internal.Secret";
    private static final Set<String> REQUIRED_MEMBERS = Set.of("type", "title", "status", "detail", "instance", "code");
    private static final String SENTINEL = "SENTINEL-7f3a";
    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private WeatherService weatherService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private final List<Long> accounts = new ArrayList<>();
    private final List<String> courseNames = new ArrayList<>();
    private String passwordHash;

    @AfterEach
    void cleanUp() {
        courseNames.forEach(name -> jdbc.update("DELETE FROM course WHERE name = ?", name));
        accounts.forEach(id -> jdbc.update("DELETE FROM account WHERE id = ?", id));
        courseNames.clear();
        accounts.clear();
    }

    @Test
    void badRequest400ListsTheInvalidFields() throws Exception {
        HttpResponse<String> malformed = send("POST", "/api/v1/admin/courses", account(Role.ADMIN),
                "application/json", "{\"name\": ");
        JsonNode problem = assertProblem(malformed, 400, "request/invalid");
        assertThat(problem.get("errors").get(0).get("field").asText()).isEqualTo("body");
        assertThat(problem.get("errors").get(0).get("code").asText()).isEqualTo("malformed");

        HttpResponse<String> invalidField = send("PUT", "/api/v1/me", account(Role.USER), "application/json",
                "{\"firstName\":\"<img src=x onerror=alert(1)>\",\"lastName\":\"Valid\"}");
        JsonNode fieldProblem = assertProblem(invalidField, 400, "request/invalid");
        assertThat(fieldProblem.get("errors").get(0).get("field").asText()).isEqualTo("firstName");
        assertThat(invalidField.body()).doesNotContain("onerror");
    }

    @Test
    void firewallRejection400IsAProblemFromTheErrorPath() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/courses;jsessionid=abc", account(Role.USER), null, null);

        assertProblem(response, 400, "request/invalid");
    }

    @Test
    void unauthenticated401() throws Exception {
        assertProblem(send("GET", "/api/v1/me", null, null, null), 401, "auth/unauthenticated");
        assertProblem(sendWithToken("GET", "/api/v1/admin/accounts", "not-a-jwt"), 401, "auth/unauthenticated");
    }

    @Test
    void accessDenied403() throws Exception {
        Account user = account(Role.USER);

        assertProblem(send("GET", "/api/v1/admin/accounts", user, null, null), 403, "auth/access-denied");
        assertProblem(send("DELETE", "/api/v1/admin/courses/1", user, null, null), 403, "auth/access-denied");
    }

    @Test
    void notFound404() throws Exception {
        Account admin = account(Role.ADMIN);

        assertProblem(send("GET", "/api/v1/no-such-route", admin, null, null), 404, "request/not-found");
        assertProblem(send("GET", "/api/v1/accounts/students", admin, null, null), 404, "request/not-found");
        assertProblem(send("DELETE", "/api/v1/admin/ip-allocations/987654321", admin, null, null), 404,
                "ip-allocation/not-found");
        assertProblem(send("DELETE", "/api/v1/admin/ip-rules/987654321", admin, null, null), 404, "ip-rule/not-found");
    }

    @Test
    void notFoundIsJsonEvenWhenHtmlIsRequested() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/no-such-page"))
                .header("Authorization", bearer(account(Role.USER)))
                .header("Accept", "text/html")
                .GET().build();

        assertProblem(http.send(request, HttpResponse.BodyHandlers.ofString()), 404, "request/not-found");
    }

    @Test
    void methodNotAllowed405() throws Exception {
        HttpResponse<String> response = send("PATCH", "/api/v1/courses", account(Role.USER), "application/json", "{}");

        assertProblem(response, 405, "request/method-not-allowed");
        assertThat(response.headers().firstValue("Allow")).hasValueSatisfying(allow -> assertThat(allow).contains("GET"));
    }

    @Test
    void conflict409() throws Exception {
        Account admin = account(Role.ADMIN);
        String name = "problem-course-" + UUID.randomUUID();
        courseNames.add(name);
        String body = "{\"name\":\"" + name + "\"}";
        assertThat(send("POST", "/api/v1/admin/courses", admin, "application/json", body).statusCode()).isEqualTo(201);

        assertProblem(send("POST", "/api/v1/admin/courses", admin, "application/json", body), 409, "request/conflict");
        assertProblem(send("DELETE", "/api/v1/admin/accounts/" + admin.getId(), admin, null, null), 409,
                "account/self-delete");
    }

    @Test
    void payloadTooLarge413() throws Exception {
        String boundary = "educore-" + UUID.randomUUID();
        String multipart = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"students.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n"
                + "a,b,c\n".repeat(1024) + "\r\n--" + boundary + "--\r\n";

        HttpResponse<String> response = send("POST", "/api/v1/admin/courses", account(Role.ADMIN),
                "multipart/form-data; boundary=" + boundary, multipart);

        assertProblem(response, 413, "request/payload-too-large");
    }

    @Test
    void unsupportedMediaType415() throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/admin/courses", account(Role.ADMIN), "text/plain",
                "name=x");

        assertProblem(response, 415, "request/unsupported-media-type");
    }

    @Test
    void locked423CarriesRetryAfter() throws Exception {
        Account user = account(Role.USER);
        // The lock applies to the (username, client network) pair that failed (R-01).
        String ip = newIp();
        for (int i = 0; i < 5; i++) {
            assertThat(login(user.getUsername(), "wrong-test-value", ip).getResponse().getStatus()).isEqualTo(401);
        }

        MvcResult locked = login(user.getUsername(), PASSWORD, ip);

        assertMockProblem(locked, 423, "auth/account-locked");
        assertThat(Long.parseLong(locked.getResponse().getHeader("Retry-After"))).isPositive();
    }

    @Test
    void tooManyRequests429CarriesRetryAfter() throws Exception {
        String ip = newIp();
        MvcResult last = null;
        for (int i = 0; i < 11; i++) {
            last = login("problem-unknown-" + UUID.randomUUID(), "wrong-test-value", ip);
        }

        assertMockProblem(last, 429, "auth/too-many-attempts");
        assertThat(Long.parseLong(last.getResponse().getHeader("Retry-After"))).isPositive();
    }

    @Test
    void internalError500CarriesOnlyTheCorrelationIdAndIsLoggedUnderIt(CapturedOutput output) throws Exception {
        when(weatherService.getCitiesWeather()).thenThrow(new IllegalStateException(LEAK));
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/weather"))
                .header("Authorization", bearer(account(Role.USER)))
                .header("X-Request-Id", "problem-it-500")
                .GET().build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        JsonNode problem = assertProblem(response, 500, "server/internal-error");
        assertThat(problem.get("correlationId").asText()).isEqualTo("problem-it-500");
        assertThat(response.headers().firstValue("X-Request-Id")).hasValue("problem-it-500");
        assertThat(problem.has("errors")).isFalse();
        assertThat(output.getAll()).contains("correlationId=problem-it-500").contains("IllegalStateException");
    }

    @Test
    void successfulResponsesAlsoCarryNosniff() throws Exception {
        when(weatherService.getCitiesWeather()).thenReturn(List.of());

        HttpResponse<String> response = send("GET", "/api/v1/weather", account(Role.USER), null, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                assertThat(type).startsWith("application/json"));
    }

    @Test
    void rejectedValuesAreNeverEchoedEvenWhenNested() throws Exception {
        Account admin = account(Role.ADMIN);
        List<HttpResponse<String>> responses = List.of(
                send("PUT", "/api/v1/me", account(Role.USER), "application/json",
                        "{\"firstName\":\"" + SENTINEL + "<b>\",\"lastName\":{\"nested\":\"" + SENTINEL + "\"}}"),
                send("POST", "/api/v1/admin/accounts/students", admin, "application/json",
                        "{\"firstName\":\"Valid\",\"studentNumber\":\"" + SENTINEL + "\",\"ipAddress\":[\""
                                + SENTINEL + "\"]}"),
                send("PUT", "/api/v1/admin/accounts/" + admin.getId() + "/role", admin, "application/json",
                        "{\"role\":\"" + SENTINEL + "\"}"),
                send("POST", "/api/v1/admin/ip-allocations", admin, "application/json",
                        "{\"type\":{\"deep\":[\"" + SENTINEL + "\"]},\"originalValue\":\"" + SENTINEL + "\"}"),
                send("POST", "/api/v1/auth/login", null, "application/json",
                        "{\"username\":\"u\",\"password\":\"" + SENTINEL + "x".repeat(200) + "\"}"),
                send("GET", "/api/v1/admin/accounts?sort=" + SENTINEL + "&direction=" + SENTINEL, admin, null, null),
                send("GET", "/api/v1/admin/accounts?size=" + SENTINEL, admin, null, null));

        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).as(response.request().uri().toString()).isEqualTo(400);
            assertThat(response.body()).as(response.request().uri().toString()).doesNotContain(SENTINEL);
            JsonNode problem = json.readTree(response.body());
            assertProblem(response, 400, problem.get("code").asText());
        }
    }

    @Test
    void jsonBodiesAboveTheLimitAre413WithAndWithoutContentLength() throws Exception {
        String large = "{\"username\":\"u\",\"password\":\"" + "x".repeat(70 * 1024) + "\"}";
        String small = "{\"username\":\"problem-nobody\",\"password\":\"wrong-test-value\"}";

        HttpResponse<String> known = send("POST", "/api/v1/auth/login", null, "application/json", large);
        assertProblem(known, 413, "request/payload-too-large");

        HttpRequest chunked = HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(
                        () -> new java.io.ByteArrayInputStream(large.getBytes(StandardCharsets.UTF_8))))
                .build();
        HttpResponse<String> streamed = http.send(chunked, HttpResponse.BodyHandlers.ofString());
        assertThat(chunked.bodyPublisher().orElseThrow().contentLength()).as("unknown length: sent chunked").isNegative();
        assertProblem(streamed, 413, "request/payload-too-large");

        HttpRequest smallChunked = HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(
                        () -> new java.io.ByteArrayInputStream(small.getBytes(StandardCharsets.UTF_8))))
                .build();
        assertProblem(http.send(smallChunked, HttpResponse.BodyHandlers.ofString()), 401, "auth/invalid-credentials");
    }

    @Test
    void corsRejectionIsAProblem() throws Exception {
        Account user = account(Role.USER);
        HttpRequest actual = HttpRequest.newBuilder(uri("/api/v1/courses"))
                .header("Authorization", bearer(user))
                .header("Origin", "https://attacker.example")
                .GET().build();
        HttpRequest preflight = HttpRequest.newBuilder(uri("/api/v1/admin/accounts"))
                .header("Origin", "https://attacker.example")
                .header("Access-Control-Request-Method", "DELETE")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();

        for (HttpRequest request : List.of(actual, preflight)) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertProblem(response, 403, "request/cors-rejected");
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        }
    }

    @Test
    void managementChainAnswersProblems() throws Exception {
        URI metrics = URI.create("http://localhost:" + managementPort + "/actuator/metrics");
        HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(metrics).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> user = http.send(HttpRequest.newBuilder(metrics)
                .header("Authorization", bearer(account(Role.USER))).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertProblem(anonymous, 401, "auth/unauthenticated");
        assertProblem(user, 403, "auth/access-denied");
    }

    @Test
    void notAcceptable406IsStillJson() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/courses"))
                .header("Authorization", bearer(account(Role.USER)))
                .header("Accept", "application/xml")
                .GET().build();

        assertProblem(http.send(request, HttpResponse.BodyHandlers.ofString()), 406, "request/not-acceptable");
    }

    @Test
    void asyncFailureCarriesTheRequestIdAsCorrelationId(CapturedOutput output) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(AsyncFailureController.PATH))
                .header("Authorization", bearer(account(Role.USER)))
                .header("X-Request-Id", "problem-it-async")
                .GET().build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        JsonNode problem = assertProblem(response, 500, "server/internal-error");
        assertThat(problem.get("correlationId").asText()).isEqualTo("problem-it-async");
        assertThat(output.getAll()).contains("correlationId=problem-it-async");
    }

    /** A test-only async endpoint (this context only) whose future fails. */
    @TestConfiguration
    static class AsyncFailureConfig {
        @Bean
        AsyncFailureController asyncFailureController() {
            return new AsyncFailureController();
        }
    }

    @RestController
    public static class AsyncFailureController {
        static final String PATH = "/api/v1/test-only/async-failure";

        @GetMapping(PATH)
        public CompletableFuture<String> fail() {
            return CompletableFuture.supplyAsync(() -> {
                throw new IllegalStateException(LEAK);
            });
        }
    }

    // ---- assertions --------------------------------------------------------------------------------------

    private JsonNode assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                assertThat(type).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE));
        assertThat(response.headers().allValues("X-Content-Type-Options")).containsExactly("nosniff");
        return assertShape(response.body(), status, code, response.request().uri().getRawPath());
    }

    private void assertMockProblem(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertShape(result.getResponse().getContentAsString(StandardCharsets.UTF_8), status, code,
                result.getRequest().getRequestURI());
    }

    /**
     * The exact member set: {@code type, title, status, detail, instance, code}, plus {@code errors} for
     * invalid requests (entries with exactly {@code field} and {@code code}) and {@code correlationId} for 5xx.
     */
    private JsonNode assertShape(String body, int status, String code, String instance) throws Exception {
        JsonNode problem = json.readTree(body);
        assertThat(problem.get("type").asText()).isEqualTo("/problems/" + code);
        assertThat(problem.get("code").asText()).isEqualTo(code);
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        assertThat(problem.get("title").asText()).isNotBlank();
        assertThat(problem.get("detail").asText()).isNotBlank();
        assertThat(problem.get("instance").asText()).isEqualTo(instance);
        Set<String> expected = new java.util.HashSet<>(REQUIRED_MEMBERS);
        if (code.equals("request/invalid") || code.equals("auth/invalid-request")) {
            expected.add("errors");
        }
        if (status >= 500) {
            expected.add("correlationId");
        }
        List<String> members = new ArrayList<>();
        Iterator<String> names = problem.fieldNames();
        names.forEachRemaining(members::add);
        assertThat(members).as(body).containsExactlyInAnyOrderElementsOf(expected);
        if (expected.contains("errors")) {
            assertThat(problem.get("errors").isArray()).isTrue();
            problem.get("errors").forEach(error -> {
                List<String> keys = new ArrayList<>();
                error.fieldNames().forEachRemaining(keys::add);
                assertThat(keys).as("error entry %s", error).containsExactlyInAnyOrder("field", "code");
            });
        }
        if (status >= 500) {
            assertThat(problem.get("correlationId").asText()).isNotBlank();
        }
        String lower = body.toLowerCase(Locale.ROOT);
        assertThat(lower).as("no internals in %s", body).doesNotContain("exception", "com.educore", "org.springframework",
                "org.hibernate", "java.", "select ", "stacktrace", "\tat ");
        return problem;
    }

    // ---- fixtures and requests ---------------------------------------------------------------------------

    private Account account(Role role) {
        if (passwordHash == null) {
            passwordHash = passwordEncoder.encode(PASSWORD);
        }
        Account account = accountRepository.save(Account.builder()
                .username("problem-" + UUID.randomUUID())
                .password(passwordHash)
                .firstName("Problem")
                .lastName(role.name())
                .studentNumber("96" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000))
                .role(role)
                .build());
        accounts.add(account.getId());
        return account;
    }

    private String bearer(Account account) {
        return "Bearer " + jwtService.issue(AuthenticatedUser.of(account)).token();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(String method, String path, Account caller, String contentType, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (caller != null) {
            request.header("Authorization", bearer(caller));
        }
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendWithToken(String method, String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).method(method, HttpRequest.BodyPublishers.noBody())
                .header("Authorization", "Bearer " + token).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private MvcResult login(String username, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("username", username, "password", password))))
                .andReturn();
    }

    private static String newIp() {
        int n = IP_SEQUENCE.getAndIncrement();
        return "10.241." + ((n >> 8) & 0xff) + "." + (n & 0xff);
    }
}
