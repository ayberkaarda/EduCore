package com.educore.authz;

import com.educore.entity.Account;
import com.educore.entity.Role;
import com.educore.repository.AccountRepository;
import com.educore.security.AuthenticatedUser;
import com.educore.security.JwtService;
import com.educore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against a real Tomcat (no MockMvc normalisation): path tricks never reach an admin handler for a USER or an
 * anonymous caller, and CORS preflights never leak data or bypass authentication.
 * <p>
 * Acceptable refusals are 400 (rejected by Tomcat or Spring Security's {@code StrictHttpFirewall}),
 * 401, 403 and 404; the assertion is that no variant answers 2xx.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PathVariantSecurityIT extends AbstractIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:3000";
    private static final String FOREIGN_ORIGIN = "https://attacker.example";

    @LocalServerPort
    private int port;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Long> createdAccounts = new ArrayList<>();

    @AfterEach
    void removeAccounts() {
        createdAccounts.forEach(accountRepository::deleteById);
        createdAccounts.clear();
    }

    @Test
    void pathVariantsOfAdminRoutesNeverAnswer2xxForUserOrAnonymous() throws Exception {
        Account admin = account(Role.ADMIN);
        Account user = account(Role.USER);
        assertThat(send("GET", "/api/v1/admin/accounts", token(admin), null, null).statusCode())
                .as("canonical route works for ADMIN").isEqualTo(200);

        List<String> variants = List.of(
                "/api/v1/admin/accounts/", "//api/v1/admin/accounts", "/api/v1//admin/accounts",
                "/api/v1/admin//accounts", "/api/v1/admin/accounts;jsessionid=x", "/api/v1/admin;x=y/accounts",
                "/api/v1/ADMIN/accounts", "/api/v1/Admin/accounts", "/API/V1/ADMIN/ACCOUNTS",
                "/api/v1/admin/accounts.json", "/api/v1/admin%2Faccounts", "/api/v1%2Fadmin%2Faccounts",
                "/api/v1/admin/%61ccounts", "/api/v1/%61dmin/accounts", "/api/v1/admin/./accounts",
                "/api/v1/me/../admin/accounts", "/api/v1/me/%2e%2e/admin/accounts", "/api/v1/admin/accounts%00",
                "/api/v1/admin/accounts/students/", "/api/v1/admin/accounts/students.json",
                "/api/v1/admin/security-events;a=b");
        for (String path : variants) {
            for (String caller : new String[]{"USER", "ANONYMOUS"}) {
                int status = send("GET", path, caller.equals("USER") ? token(user) : null, null, null).statusCode();
                assertThat(status).as("GET %s as %s", path, caller).isIn(400, 401, 403, 404);
            }
        }
    }

    @Test
    void pathVariantsOfTheRoleChangeNeverPromoteAUser() throws Exception {
        Account user = account(Role.USER);
        String base = "/api/v1/admin/accounts/" + user.getId() + "/role";
        List<String> variants = List.of(base, base + "/", base + ";jsessionid=x", base + ".json",
                "/api/v1/ADMIN/accounts/" + user.getId() + "/role", "/api/v1//admin/accounts/" + user.getId() + "/role",
                "/api/v1/admin/accounts/" + user.getId() + "%2Frole",
                "/api/v1/me/../admin/accounts/" + user.getId() + "/role");
        for (String path : variants) {
            HttpResponse<String> response = send("PUT", path, token(user), null, "{\"role\":\"ADMIN\"}");
            assertThat(response.statusCode()).as("PUT %s as the USER itself", path).isIn(400, 401, 403, 404, 405);
        }
        assertThat(accountRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    @Test
    void preflightFromAnAllowedOriginCarriesNoDataAndTheActualRequestStillNeedsAToken() throws Exception {
        HttpResponse<String> preflight = preflight("/api/v1/admin/accounts", ALLOWED_ORIGIN);
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(header(preflight, "Access-Control-Allow-Origin")).contains(ALLOWED_ORIGIN);
        assertThat(preflight.body()).isEmpty();

        HttpResponse<String> anonymous = send("GET", "/api/v1/admin/accounts", null, ALLOWED_ORIGIN, null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.body()).doesNotContain("username");

        Account user = account(Role.USER);
        assertThat(send("GET", "/api/v1/admin/accounts", token(user), ALLOWED_ORIGIN, null).statusCode())
                .isEqualTo(403);
    }

    @Test
    void foreignOriginsAreRejectedForPreflightAndActualRequests() throws Exception {
        HttpResponse<String> preflight = preflight("/api/v1/admin/accounts", FOREIGN_ORIGIN);
        assertThat(preflight.statusCode()).isEqualTo(403);
        assertThat(header(preflight, "Access-Control-Allow-Origin")).isEmpty();

        // Even a valid ADMIN token does not produce a readable response for a foreign origin.
        Account admin = account(Role.ADMIN);
        HttpResponse<String> actual = send("GET", "/api/v1/admin/accounts", token(admin), FOREIGN_ORIGIN, null);
        assertThat(actual.statusCode()).isEqualTo(403);
        assertThat(header(actual, "Access-Control-Allow-Origin")).isEmpty();
        assertThat(actual.body()).doesNotContain("username");
    }

    private HttpResponse<String> preflight(String path, String origin) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String token, String origin, String json)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Optional<String> header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name);
    }

    private String token(Account account) {
        return jwtService.issue(AuthenticatedUser.of(account)).token();
    }

    private Account account(Role role) {
        Account account = accountRepository.save(Account.builder()
                .username("authz-path-" + UUID.randomUUID())
                /* TEST DATA ONLY */
                .password(passwordEncoder.encode("path-variant-it-only-value"))
                .firstName("Path")
                .lastName("Variant")
                .role(role)
                .deleted(0)
                .build());
        createdAccounts.add(account.getId());
        return account;
    }
}
