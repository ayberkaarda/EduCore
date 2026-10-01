package com.educore.authz;

import com.educore.account.AccountAdminService;
import com.educore.entity.Account;
import com.educore.entity.Role;
import com.educore.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** A USER can never change any role, including their own, by any route or by a forged token claim. */
class PrivilegeEscalationIT extends AuthzIntegrationSupport {

    @Autowired
    private AccountAdminService accountAdminService;

    @Test
    void userCannotPromoteThemself() throws Exception {
        Account user = account(Role.USER);

        MvcResult result = perform(user, put("/api/v1/admin/accounts/" + user.getId() + "/role"),
                map("role", "ADMIN"));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(roleOf(user)).isEqualTo(Role.USER);
        assertThat(roleChangedEvents(user)).isZero();
    }

    @Test
    void userCannotChangeAnotherAccountsRoleInEitherDirection() throws Exception {
        Account user = account(Role.USER);
        Account otherUser = account(Role.USER);
        Account admin = account(Role.ADMIN);

        assertThat(perform(user, put("/api/v1/admin/accounts/" + otherUser.getId() + "/role"), map("role", "ADMIN"))
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(perform(user, put("/api/v1/admin/accounts/" + admin.getId() + "/role"), map("role", "USER"))
                .getResponse().getStatus()).isEqualTo(403);

        assertThat(roleOf(otherUser)).isEqualTo(Role.USER);
        assertThat(roleOf(admin)).isEqualTo(Role.ADMIN);
    }

    @Test
    void roleInTheOwnProfileBodyIsIgnored() throws Exception {
        Account user = account(Role.USER);

        MvcResult result = perform(user, put("/api/v1/me"),
                map("firstName", "Still", "lastName", "User", "role", "ADMIN"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("role").asText()).isEqualTo("USER");
        assertThat(roleOf(user)).isEqualTo(Role.USER);
    }

    @Test
    void aTokenClaimingAdminDoesNotGrantAdminBecauseTheRoleComesFromTheDatabase() throws Exception {
        Account user = account(Role.USER);
        String forged = jwtService.issue(new AuthenticatedUser(user.getId(), user.getUsername(), Role.ADMIN)).token();

        MvcResult result = mockMvc.perform(put("/api/v1/admin/accounts/" + user.getId() + "/role")
                        .with(from(newIp()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)
                        .contentType("application/json")
                        .content(json.writeValueAsString(map("role", "ADMIN"))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(roleOf(user)).isEqualTo(Role.USER);
    }

    @Test
    void theServiceLayerRefusesAUserEvenWithoutTheController() {
        Account user = account(Role.USER);
        authenticateAs(AuthenticatedUser.of(user));

        assertThatThrownBy(() -> accountAdminService.changeRole(AuthenticatedUser.of(user), user.getId(), Role.ADMIN))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(roleOf(user)).isEqualTo(Role.USER);
    }

    @Test
    void anAdminCannotChangeTheirOwnRole() throws Exception {
        Account admin = account(Role.ADMIN);

        MvcResult result = perform(admin, put("/api/v1/admin/accounts/" + admin.getId() + "/role"),
                map("role", "USER"));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(result).get("type").asText()).endsWith("/account/self-role-change");
        assertThat(roleOf(admin)).isEqualTo(Role.ADMIN);
    }

    @Test
    void aDemotedAdminLosesAdminAccessOnTheNextRequest() throws Exception {
        Account admin = account(Role.ADMIN);
        Account demoted = account(Role.ADMIN);
        String demotedToken = bearer(demoted);

        assertThat(perform(admin, put("/api/v1/admin/accounts/" + demoted.getId() + "/role"), map("role", "USER"))
                .getResponse().getStatus()).isEqualTo(200);

        MvcResult result = mockMvc.perform(get("/api/v1/admin/accounts").with(from(newIp()))
                .header(HttpHeaders.AUTHORIZATION, demotedToken)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void anUnknownRoleValueIsRejected() throws Exception {
        Account admin = account(Role.ADMIN);
        Account user = account(Role.USER);

        MvcResult result = perform(admin, put("/api/v1/admin/accounts/" + user.getId() + "/role"),
                map("role", "SUPERUSER"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(roleOf(user)).isEqualTo(Role.USER);
    }

    private Role roleOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getRole();
    }

    private long roleChangedEvents(Account target) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM security_event WHERE type = 'ROLE_CHANGED' AND target_account_id = ?",
                Long.class, target.getId());
        return count == null ? 0 : count;
    }
}
