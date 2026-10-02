package com.educore.attacks;

import com.educore.authz.AuthzIntegrationSupport;
import com.educore.entity.Account;
import com.educore.entity.Role;
import com.educore.security.AuthenticatedUser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Attacker holding a valid USER token who tries to gain ADMIN power by every route-level trick.
 *
 * <p>Attack: promote oneself or another account; present a token whose {@code roles} claim says ADMIN; call
 * admin-only routes with a USER token; smuggle {@code role} into the self-service profile body; tamper the HTTP
 * verb; and send method-override headers ({@code X-HTTP-Method-Override}, {@code X-Method-Override},
 * {@code _method}) to turn a reading request into a privileged write.
 *
 * <p>Expected defence: admin routes require {@code ROLE_ADMIN} resolved from the database on every request (the
 * token claim is ignored), the profile body ignores unknown fields, method-override headers are not honoured, so
 * every attempt is 401/403/404/405 and no role ever changes.
 */
@Tag("attack")
class PrivilegeEscalationIT extends AuthzIntegrationSupport {

    private Role roleOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getRole();
    }

    @Test
    void aUserCannotPromoteThemselfOrAnyoneElse() throws Exception {
        Account attacker = account(Role.USER);
        Account victim = account(Role.USER);

        assertThat(perform(attacker, put("/api/v1/admin/accounts/" + attacker.getId() + "/role"),
                map("role", "ADMIN")).getResponse().getStatus()).isEqualTo(403);
        assertThat(perform(attacker, put("/api/v1/admin/accounts/" + victim.getId() + "/role"),
                map("role", "ADMIN")).getResponse().getStatus()).isEqualTo(403);
        assertThat(roleOf(attacker)).isEqualTo(Role.USER);
        assertThat(roleOf(victim)).isEqualTo(Role.USER);
    }

    @Test
    void aTokenClaimingAdminRolesDoesNotGrantAdmin() throws Exception {
        Account attacker = account(Role.USER);
        String forged = jwtService.issue(new AuthenticatedUser(attacker.getId(), attacker.getUsername(), Role.ADMIN))
                .token();

        MvcResult adminList = mockMvc.perform(get("/api/v1/admin/accounts").with(from(newIp()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)).andReturn();
        MvcResult promote = mockMvc.perform(put("/api/v1/admin/accounts/" + attacker.getId() + "/role")
                .with(from(newIp())).header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)
                .contentType("application/json").content(json.writeValueAsString(map("role", "ADMIN")))).andReturn();

        assertThat(adminList.getResponse().getStatus()).isEqualTo(403);
        assertThat(promote.getResponse().getStatus()).isEqualTo(403);
        assertThat(roleOf(attacker)).isEqualTo(Role.USER);
    }

    @Test
    void everyAdminWriteRouteIsClosedToAUserToken() throws Exception {
        Account attacker = account(Role.USER);
        Account victim = account(Role.USER);

        assertThat(perform(attacker, get("/api/v1/admin/accounts"), null).getResponse().getStatus()).isEqualTo(403);
        assertThat(perform(attacker, post("/api/v1/admin/courses"),
                map("name", "Attack 101", "term", "2026/1", "instructor", "X")).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(perform(attacker, post("/api/v1/admin/accounts/students"),
                map("firstName", "Mallory")).getResponse().getStatus()).isEqualTo(403);
        assertThat(perform(attacker, put("/api/v1/admin/accounts/" + victim.getId()),
                map("firstName", "Taken", "lastName", "Over")).getResponse().getStatus()).isEqualTo(403);
        assertThat(perform(attacker, delete("/api/v1/admin/accounts/" + victim.getId()), null)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(perform(attacker, post("/api/v1/admin/ip-rules"),
                map("kind", "STATIC", "value", "203.0.113.9")).getResponse().getStatus()).isEqualTo(403);
        assertThat(perform(attacker, get("/api/v1/admin/security-events"), null).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(accountRepository.findById(victim.getId()).orElseThrow().getFirstName()).isNotEqualTo("Taken");
    }

    @Test
    void roleSmuggledIntoTheSelfServiceProfileBodyIsIgnored() throws Exception {
        Account attacker = account(Role.USER);

        MvcResult result = perform(attacker, put("/api/v1/me"),
                map("firstName", "Still", "lastName", "User", "role", "ADMIN", "status", "ACTIVE",
                        "mustChangePassword", false));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("role").asText()).isEqualTo("USER");
        assertThat(roleOf(attacker)).isEqualTo(Role.USER);
    }

    @Test
    void httpVerbTamperingDoesNotReachThePrivilegedHandler() throws Exception {
        Account attacker = account(Role.USER);

        // POST to a PUT-only admin route: wrong method, never a successful promotion.
        MvcResult wrongVerb = perform(attacker, post("/api/v1/admin/accounts/" + attacker.getId() + "/role"),
                map("role", "ADMIN"));
        assertThat(wrongVerb.getResponse().getStatus()).isIn(403, 405);
        assertThat(roleOf(attacker)).isEqualTo(Role.USER);
    }

    @Test
    void methodOverrideHeadersCannotTurnAReadIntoAPrivilegedWrite() throws Exception {
        Account attacker = account(Role.USER);
        Account victim = account(Role.USER);

        for (String header : new String[]{"X-HTTP-Method-Override", "X-HTTP-Method", "X-Method-Override"}) {
            MvcResult overridden = mockMvc.perform(get("/api/v1/admin/accounts/" + victim.getId() + "/role")
                    .with(from(newIp())).header(HttpHeaders.AUTHORIZATION, bearer(attacker))
                    .header(header, "PUT").contentType("application/json")
                    .content(json.writeValueAsString(map("role", "ADMIN")))).andReturn();
            assertThat(overridden.getResponse().getStatus()).as(header).isIn(403, 404, 405);
        }
        assertThat(roleOf(victim)).isEqualTo(Role.USER);
    }
}
