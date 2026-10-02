package com.educore.auth;

import com.educore.entity.Account;
import com.educore.entity.Role;
import com.educore.repository.AccountRepository;
import com.educore.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared helpers for the auth integration tests. Every test creates its own accounts and uses its own
 * client IP, so lockout and per-IP throttling state never leaks between tests sharing the Spring context.
 */
@AutoConfigureMockMvc
abstract class AuthIntegrationSupport extends AbstractIntegrationTest {

    /** Allowed by educore.cors.allowed-origins in application-test.yml. */
    static final String ALLOWED_ORIGIN = "http://localhost:3000";
    /** TEST DATA ONLY: password of the accounts created by these tests. */
    static final String PASSWORD = "integration-test-only-value";

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected AccountRepository accountRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected UsernameHasher usernameHasher;

    protected final ObjectMapper json = new ObjectMapper();

    private final List<Long> createdAccountIds = new ArrayList<>();

    /** Removes the accounts this test created (their refresh tokens cascade), keeping the shared seed intact. */
    @AfterEach
    void deleteCreatedAccounts() {
        createdAccountIds.forEach(accountRepository::deleteById);
        createdAccountIds.clear();
    }

    /** A client IP no other test uses. */
    static String newIp() {
        int n = IP_SEQUENCE.getAndIncrement();
        return "10." + ((n >> 16) & 0xff) + "." + ((n >> 8) & 0xff) + "." + (n & 0xff);
    }

    Account createAccount(boolean mustChangePassword) {
        return createAccountWithHash(passwordEncoder.encode(PASSWORD), mustChangePassword);
    }

    Account createAccountWithHash(String passwordHash, boolean mustChangePassword) {
        String username = "it-" + UUID.randomUUID();
        Account account = accountRepository.save(Account.builder()
                .username(username)
                .password(passwordHash)
                .firstName("Test")
                .lastName("User")
                .role(Role.USER)
                .mustChangePassword(mustChangePassword)
                .build());
        createdAccountIds.add(account.getId());
        return account;
    }

    MvcResult login(String username, String password, String ip) throws Exception {
        return mockMvc.perform(loginRequest(username, password, ip)).andReturn();
    }

    MockHttpServletRequestBuilder loginRequest(String username, String password, String ip) throws Exception {
        String body = json.writeValueAsString(new LoginRequest(username, password));
        return post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    MockHttpServletRequestBuilder refreshRequest(String refreshToken, String ip) {
        MockHttpServletRequestBuilder builder = post("/api/v1/auth/refresh")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);
        return refreshToken == null ? builder : builder.cookie(new Cookie(RefreshCookies.NAME, refreshToken));
    }

    MockHttpServletRequestBuilder meRequest(String accessToken) {
        return get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
    }

    JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    static String accessToken(JsonNode body) {
        return body.get("accessToken").asText();
    }

    /** The {@code educore_rt} Set-Cookie header of {@code result}. */
    static String refreshSetCookie(MvcResult result) {
        List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(RefreshCookies.NAME + "="))
                .toList();
        if (cookies.size() != 1) {
            throw new AssertionError("Expected exactly one " + RefreshCookies.NAME + " cookie, got " + cookies);
        }
        return cookies.get(0);
    }

    /** The refresh token value set by {@code result}. */
    static String refreshToken(MvcResult result) {
        String header = refreshSetCookie(result);
        return header.substring(RefreshCookies.NAME.length() + 1, header.indexOf(';'));
    }

    long countEvents(String type, Long targetAccountId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM security_event WHERE type = ? AND target_account_id = ?",
                Long.class, type, targetAccountId);
        return count == null ? 0 : count;
    }
}
