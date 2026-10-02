package com.educore.common.logging;

import com.educore.auth.AuthProblemException;
import com.educore.auth.AuthService;
import com.educore.auth.ClientInfo;
import com.educore.auth.LoginRequest;
import com.educore.entity.Account;
import com.educore.entity.Role;
import com.educore.repository.AccountRepository;
import com.educore.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real log output of a running application in the production log format (profile {@code json-logs}: ECS JSON,
 * one object per line) with Spring Web and Spring Security at DEBUG: passwords, bearer tokens and refresh
 * cookies never reach the log, user-controlled values are sanitised, every line is valid JSON and request lines
 * carry {@code requestId} (and {@code userId} once authenticated). Requests go through a real Tomcat.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "logging.level.org.springframework.web=DEBUG", "logging.level.org.springframework.security=DEBUG"})
@ActiveProfiles("json-logs")
@ExtendWith(OutputCaptureExtension.class)
// Own context (profile + DEBUG levels): closed afterwards so its connection pool does not stay open.
@DirtiesContext
class LoggingIT extends AbstractIntegrationTest {

    /** TEST DATA ONLY: plain lower-case phrases that no masking rule would hide if they were ever logged. */
    private static final String PASSWORD = "logging it only old phrase";
    private static final String NEW_PASSWORD = "logging it only new phrase";
    private static final String FOREIGN_BEARER = "logging-it-foreign-bearer-value";

    @LocalServerPort
    private int port;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuthService authService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private final List<Long> accounts = new ArrayList<>();

    @AfterEach
    void deleteAccounts() {
        accounts.forEach(accountRepository::deleteById);
        accounts.clear();
    }

    @Test
    void loginAndPasswordChangeNeverLogPasswordsTokensOrCookies(CapturedOutput output) throws Exception {
        Account account = account();
        HttpResponse<String> login = send(post("/api/v1/auth/login", "logging-it-login",
                Map.of("username", account.getUsername(), "password", PASSWORD))
                .header("Authorization", "Bearer " + FOREIGN_BEARER));
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String accessToken = json.readTree(login.body()).get("accessToken").asText();
        String refreshCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        String refreshValue = refreshCookie.substring(refreshCookie.indexOf('=') + 1, refreshCookie.indexOf(';'));

        HttpResponse<String> changed = send(post("/api/v1/auth/password", "logging-it-password",
                Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD))
                .header("Authorization", "Bearer " + accessToken)
                .header("Cookie", refreshCookie.substring(0, refreshCookie.indexOf(';'))));
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);

        String all = output.getAll();
        assertThat(all).doesNotContain(PASSWORD).doesNotContain(NEW_PASSWORD).doesNotContain(FOREIGN_BEARER)
                .doesNotContain(accessToken).doesNotContain(refreshValue)
                .doesNotContain(accessToken.substring(accessToken.lastIndexOf('.') + 1))
                // Nothing even tried to log them: the masking net did not have to catch a token.
                .doesNotContain(PiiMasking.REDACTED_JWT);

        List<JsonNode> lines = jsonLines(output);
        JsonNode success = only(lines, "SECURITY_EVENT type=AUTH_LOGIN_SUCCESS actor=" + account.getId() + " ");
        assertThat(success.get("requestId").asText()).isEqualTo("logging-it-login");
        assertThat(success.has("userId")).as("login itself is unauthenticated").isFalse();

        JsonNode passwordChanged = only(lines, "SECURITY_EVENT type=PASSWORD_CHANGED actor=" + account.getId() + " ");
        assertThat(passwordChanged.get("requestId").asText()).isEqualTo("logging-it-password");
        assertThat(passwordChanged.get("userId").asText()).isEqualTo(account.getId().toString());
        assertThat(passwordChanged.at("/log/level").asText()).isEqualTo("INFO");
        assertThat(passwordChanged.at("/log/logger").asText()).isEqualTo("com.educore.security.audit.AuditService");
        assertThat(passwordChanged.get("@timestamp").asText()).isNotBlank();
        assertThat(passwordChanged.at("/service/name").asText()).isEqualTo("educore");
        assertThat(passwordChanged.at("/ecs/version").asText()).isNotBlank();
    }

    @Test
    void crLfInjectedUsernameIsRejectedAndForgesNoLogLine(CapturedOutput output) throws Exception {
        String forged = "FORGED " + UUID.randomUUID() + " level=INFO SECURITY_EVENT type=AUTH_LOGIN_SUCCESS";

        HttpResponse<String> response = send(post("/api/v1/auth/login", "logging-it-crlf",
                Map.of("username", "eve\r\n" + forged, "password", "irrelevant-value")));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(output.getAll().lines()).noneMatch(line -> line.startsWith("FORGED"));
        assertThat(output.getAll()).doesNotContain("\r\n" + forged).doesNotContain("\n" + forged);
        jsonLines(output);
    }

    @Test
    void unicodeLineSeparatorsInTheUsernameAreRejectedToo(CapturedOutput output) throws Exception {
        HttpResponse<String> response = send(post("/api/v1/auth/login", "logging-it-separator",
                Map.of("username", "eve\u2028FORGED\u0085level=INFO\u202e", "password", "irrelevant-value")));

        assertThat(response.statusCode()).isEqualTo(400);
        // Spring's DEBUG output of the binding error quotes the rejected value; it is redacted.
        assertThat(output.getAll()).doesNotContain("rejected value [eve");
        assertThat(output.getAll()).doesNotContain("\u2028").doesNotContain("\u0085").doesNotContain("\u202e");
        jsonLines(output);
    }

    /**
     * Validation stops control characters at the HTTP boundary, so the service is called directly (as any other
     * caller would) to prove the failed-login line itself is sanitised.
     */
    @Test
    void crLfInjectedUsernameReachingTheServiceIsSanitisedInTheFailedLoginLine(CapturedOutput output) throws Exception {
        String marker = "FORGED" + ThreadLocalRandom.current().nextInt(1000, 10_000);
        String username = "eve\r\n" + marker + " level=INFO\u2028type=AUTH_LOGIN_SUCCESS\u202e";
        MDC.put(MdcKeys.REQUEST_ID, "logging-it-service");
        try {
            assertThatThrownBy(() -> authService.login(new LoginRequest(username, "irrelevant-value"),
                    new ClientInfo("10.250.0.1", "LoggingIT")))
                    .isInstanceOf(AuthProblemException.class);
        } finally {
            MDC.remove(MdcKeys.REQUEST_ID);
        }

        List<JsonNode> lines = jsonLines(output);
        JsonNode failure = only(lines, "LOGIN_FAILED reason=bad_credentials username=eve__" + marker);
        assertThat(failure.get("message").asText()).isEqualTo(
                "LOGIN_FAILED reason=bad_credentials username=eve__" + marker + " level=INFO_type=AUTH_LOGIN_SUCCESS_");
        assertThat(failure.get("requestId").asText()).isEqualTo("logging-it-service");
        assertThat(output.getAll().lines()).noneMatch(line -> line.startsWith(marker));
        assertThat(output.getAll()).doesNotContain("\u2028").doesNotContain("\u202e");
    }

    @Test
    void requestIdHeaderIsEchoedWhenSafeAndReplacedOtherwise() throws Exception {
        assertThat(requestIdFor("client-trace.0042_ok")).isEqualTo("client-trace.0042_ok");
        assertGeneratedUuid(requestIdFor("x".repeat(65)));
        assertGeneratedUuid(requestIdFor("<script>alert(1)</script>"));
        assertGeneratedUuid(requestIdFor("id with spaces"));
        assertGeneratedUuid(requestIdFor(null));
    }

    private String requestIdFor(String incoming) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/v1/auth/me")).GET();
        if (incoming != null) {
            request.header("X-Request-Id", incoming);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        return response.headers().firstValue("X-Request-Id").orElseThrow();
    }

    private static void assertGeneratedUuid(String id) {
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
    }

    /** Every non-empty captured line, parsed: the JSON format writes nothing else to the console. */
    private List<JsonNode> jsonLines(CapturedOutput output) throws Exception {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : output.getOut().split("\\R")) {
            if (!line.isBlank()) {
                lines.add(json.readTree(line));
            }
        }
        assertThat(lines).isNotEmpty();
        return lines;
    }

    private static JsonNode only(List<JsonNode> lines, String messagePrefix) {
        List<JsonNode> matching = lines.stream()
                .filter(line -> line.path("message").asText().startsWith(messagePrefix))
                .toList();
        assertThat(matching).as("log lines starting with '%s'", messagePrefix).hasSize(1);
        return matching.get(0);
    }

    private HttpRequest.Builder post(String path, String requestId, Map<String, String> body) throws Exception {
        return HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-Request-Id", requestId)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private Account account() {
        Account account = accountRepository.save(Account.builder()
                .username("logging-" + UUID.randomUUID())
                .password(passwordEncoder.encode(PASSWORD))
                .firstName("Logging")
                .lastName("Test")
                .studentNumber("97" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000))
                .role(Role.USER)
                .build());
        accounts.add(account.getId());
        return account;
    }
}
