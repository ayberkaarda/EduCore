package com.educore.authz;

import com.educore.auth.UsernameHasher;
import com.educore.entity.Account;
import com.educore.entity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * R-01: {@code POST /api/v1/admin/accounts/{id}/unlock-login} ends every lockout of an account at once by clearing
 * its failed attempts (successes, which mark trusted networks, stay); audited {@code ACCOUNT_LOGIN_UNLOCKED}.
 */
class LoginUnlockIT extends AuthzIntegrationSupport {

    @Autowired
    private UsernameHasher usernameHasher;

    private int login(Account account, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").with(from(ip)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", account.getUsername(),
                                "password", password))))
                .andReturn().getResponse().getStatus();
    }

    private long attempts(Account account, boolean success) {
        return jdbc.queryForObject("SELECT count(*) FROM login_attempt WHERE username_hash = ? AND success = ?",
                Long.class, usernameHasher.hash(account.getUsername()), success);
    }

    @Test
    void anAdminUnlockClearsTheFailuresAndIsAudited() throws Exception {
        Account admin = account(Role.ADMIN);
        Account user = account(Role.USER);
        String ip = newIp();
        assertThat(login(user, PASSWORD, ip)).isEqualTo(200);
        for (int i = 0; i < 5; i++) {
            assertThat(login(user, "wrong-value-for-test", ip)).isEqualTo(401);
        }
        assertThat(login(user, PASSWORD, ip)).as("locked pair").isEqualTo(423);

        MvcResult unlocked = perform(admin, post("/api/v1/admin/accounts/" + user.getId() + "/unlock-login"), null);

        assertThat(unlocked.getResponse().getStatus()).isEqualTo(204);
        assertThat(attempts(user, false)).isZero();
        assertThat(attempts(user, true)).as("successes are kept").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM security_event WHERE type = 'ACCOUNT_LOGIN_UNLOCKED' "
                + "AND actor_account_id = ? AND target_account_id = ? AND (details ->> 'clearedFailures')::int = 5",
                Long.class, admin.getId(), user.getId())).isOne();
        assertThat(login(user, PASSWORD, ip)).as("the pair is unlocked").isEqualTo(200);
    }

    @Test
    void onlyAnAdminCanUnlockAndUnknownAccountsAre404() throws Exception {
        Account admin = account(Role.ADMIN);
        Account user = account(Role.USER);

        MvcResult self = perform(user, post("/api/v1/admin/accounts/" + user.getId() + "/unlock-login"), null);
        assertThat(self.getResponse().getStatus()).isEqualTo(403);
        assertThat(perform(null, post("/api/v1/admin/accounts/" + user.getId() + "/unlock-login"), null)
                .getResponse().getStatus()).isEqualTo(401);
        MvcResult missing = perform(admin, post("/api/v1/admin/accounts/" + Long.MAX_VALUE + "/unlock-login"), null);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(missing).get("code").asText()).isEqualTo("account/not-found");
    }
}
