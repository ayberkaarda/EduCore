package com.educore.config;

import com.educore.entity.Account;
import com.educore.entity.Role;
import com.educore.repository.AccountRepository;
import com.educore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The bootstrap ADMIN is created only when no ADMIN exists. Each test runs in a rolled-back transaction,
 * so the seeded data seen by other integration tests is unchanged.
 */
@Transactional
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "educore.bootstrap.admin.username=" + AdminBootstrapIT.BOOTSTRAP_USERNAME,
        "educore.bootstrap.admin.password=" + AdminBootstrapIT.BOOTSTRAP_PASSWORD
})
class AdminBootstrapIT extends AbstractIntegrationTest {

    static final String BOOTSTRAP_USERNAME = "bootstrap-admin";
    /** Test data only: never used outside this test. */
    static final String BOOTSTRAP_PASSWORD = "test-only-bootstrap-password";

    @Autowired
    private AdminBootstrap adminBootstrap;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void doesNothingWhenAnAdminAlreadyExists() {
        // The test seed contains an ADMIN, so the startup run must not have created another one.
        assertThat(accountRepository.existsByRole(Role.ADMIN)).isTrue();
        assertThat(accountRepository.findByUsername(BOOTSTRAP_USERNAME)).isEmpty();

        adminBootstrap.run(new DefaultApplicationArguments());

        assertThat(accountRepository.findByUsername(BOOTSTRAP_USERNAME)).isEmpty();
    }

    @Test
    void createsAdminWithEncodedPasswordWhenNoAdminExists(CapturedOutput output) {
        accountRepository.findAll().stream()
                .filter(account -> account.getRole() == Role.ADMIN)
                .forEach(account -> account.setRole(Role.USER));
        accountRepository.flush();
        assertThat(accountRepository.existsByRole(Role.ADMIN)).isFalse();

        adminBootstrap.run(new DefaultApplicationArguments());

        Account admin = accountRepository.findByUsername(BOOTSTRAP_USERNAME).orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.isMustChangePassword()).isTrue();
        assertThat(admin.getPassword()).isNotEqualTo(BOOTSTRAP_PASSWORD);
        assertThat(passwordEncoder.matches(BOOTSTRAP_PASSWORD, admin.getPassword())).isTrue();
        assertThat(output).contains("ADMIN_BOOTSTRAPPED").doesNotContain(BOOTSTRAP_PASSWORD);
    }

    /**
     * R-03 / AC-04: the bootstrap password signs in, but only into the password-change scope; the ADMIN API opens
     * once the bootstrap ADMIN chose its own password. MockMvc runs in this test's (rolled-back) transaction.
     */
    @Test
    void theBootstrapAdminMustChangeThePasswordBeforeUsingTheAdminApi() throws Exception {
        accountRepository.findAll().stream()
                .filter(account -> account.getRole() == Role.ADMIN)
                .forEach(account -> account.setRole(Role.USER));
        accountRepository.flush();
        adminBootstrap.run(new DefaultApplicationArguments());

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(peer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", BOOTSTRAP_USERNAME, "password",
                        BOOTSTRAP_PASSWORD)))).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        JsonNode session = json.readTree(login.getResponse().getContentAsString());
        assertThat(session.get("user").get("role").asText()).isEqualTo("ADMIN");
        assertThat(session.get("user").get("mustChangePassword").asBoolean()).isTrue();
        String token = session.get("accessToken").asText();

        MvcResult denied = mockMvc.perform(get("/api/v1/admin/accounts").with(peer())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(json.readTree(denied.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("account/password-change-required");
        assertThat(mockMvc.perform(get("/api/v1/auth/me").with(peer())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn().getResponse().getStatus())
                .isEqualTo(200);

        MvcResult changed = mockMvc.perform(post("/api/v1/auth/password").with(peer())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword", BOOTSTRAP_PASSWORD,
                        "newPassword", "bootstrap-own-choice-2026")))).andReturn();
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        String full = json.readTree(changed.getResponse().getContentAsString()).get("accessToken").asText();
        assertThat(mockMvc.perform(get("/api/v1/admin/accounts").with(peer())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + full)).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    private static RequestPostProcessor peer() {
        return request -> {
            request.setRemoteAddr("10.250.0." + (1 + (int) (Math.random() * 250)));
            return request;
        };
    }
}
