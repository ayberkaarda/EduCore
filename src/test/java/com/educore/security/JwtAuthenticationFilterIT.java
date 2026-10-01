package com.educore.security;

import com.educore.config.EduCoreProperties;
import com.educore.entity.Role;
import com.educore.repository.AccountRepository;
import com.educore.support.AbstractIntegrationTest;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Bad bearer tokens never cause a 500: the request continues unauthenticated. */
@AutoConfigureMockMvc
class JwtAuthenticationFilterIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private EduCoreProperties properties;

    @Autowired
    private AccountRepository accountRepository;

    private List<String> badAuthorizationHeaders() {
        byte[] otherKey = new byte[48];
        new SecureRandom().nextBytes(otherKey);
        JwtService expiredIssuer = new JwtService(properties.security().jwt(),
                Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC));
        JwtService foreignIssuer = new JwtService(new EduCoreProperties.Jwt(
                Base64.getEncoder().encodeToString(otherKey), null, "educore", "educore-api", Duration.ofMinutes(15)),
                Clock.systemUTC());
        AuthenticatedUser admin = AuthenticatedUser.of(accountRepository.findByUsername("admin").orElseThrow());

        List<String> headers = new ArrayList<>(List.of(
                "Bearer ", "Bearer    ", "Bearer abc", "Bearer a.b.c", "Bearer ....", "Bearer " + "x".repeat(10_000),
                "Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0.",
                "Bearer " + Jwts.builder().subject("1").signWith(Keys.hmacShaKeyFor(otherKey)).compact(),
                "Bearer " + expiredIssuer.issue(admin).token(),
                "Bearer " + foreignIssuer.issue(admin).token(),
                "Bearer " + jwtService.issue(new AuthenticatedUser(Long.MAX_VALUE, "ghost", Role.ADMIN)).token(),
                "Bearer ~~~.~~~.~~~", "Bearer null", "bearer lowercase-scheme-is-ignored"));
        String valid = jwtService.issue(admin).token();
        headers.add("Bearer " + valid.substring(0, valid.length() - 3) + "abc");
        headers.add("Bearer " + valid + "." + valid);
        return headers;
    }

    @Test
    void protectedEndpointsAnswer401ForEveryBadToken() throws Exception {
        for (String header : badAuthorizationHeaders()) {
            int me = mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, header))
                    .andReturn().getResponse().getStatus();
            int students = mockMvc.perform(get("/api/v1/admin/accounts/students").header(HttpHeaders.AUTHORIZATION, header))
                    .andReturn().getResponse().getStatus();
            String shown = header.length() > 60 ? header.substring(0, 60) + "..." : header;
            assertThat(me).as("/me with %s", shown).isEqualTo(401);
            assertThat(students).as("/admin/accounts/students with %s", shown).isEqualTo(401);
        }
    }

    @Test
    void controlCharactersAreRejectedByTheHttpFirewallWithoutA500() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer \u0000\u0001\u0002"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publicEndpointsStillWorkWithABadToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("10.250.0.1");
                            return request;
                        })
                        .header(HttpHeaders.AUTHORIZATION, "Bearer a.b.c")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"REMOVED-DB-PASSWORD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void validTokenAuthenticatesWithTheTypedPrincipal() throws Exception {
        String token = jwtService.issue(AuthenticatedUser.of(accountRepository.findByUsername("admin").orElseThrow()))
                .token();

        mockMvc.perform(get("/api/v1/admin/accounts/students").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void principalIdDrivesOwnershipAndTheDatabaseRoleDrivesAdminAccess() throws Exception {
        var student = accountRepository.findByUsername("ayberk").orElseThrow();
        String token = jwtService.issue(AuthenticatedUser.of(student)).token();

        // The /me routes act on principal.id; the admin routes are closed to a USER even for their own id.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/me/enrollments/999999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/admin/accounts/" + student.getId() + "/enrollments/999999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
