package com.educore.authz;

import com.educore.account.AccountAdminService;
import com.educore.common.web.ApiProblemException;
import com.educore.entity.Account;
import com.educore.entity.AccountStatus;
import com.educore.entity.Role;
import com.educore.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * An ADMIN cannot demote or delete themself, and the last active ADMIN can be neither demoted nor deleted
 * (soft delete included), also under concurrent requests.
 * <p>
 * Through the API another ADMIN is always the actor, so a lone ADMIN can only be reached by a self-targeted
 * request (rejected first) or by two ADMINs removing each other at the same time. Tests that need a single
 * active ADMIN temporarily soft-delete every other ADMIN (seed included) and restore them afterwards.
 */
class LastAdminGuardIT extends AuthzIntegrationSupport {

    @Autowired
    private AccountAdminService accountAdminService;

    @Test
    void anAdminCannotDeleteTheirOwnAccount() throws Exception {
        Account admin = account(Role.ADMIN);

        MvcResult result = perform(admin, delete("/api/v1/admin/accounts/" + admin.getId()), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(result).get("type").asText()).endsWith("/account/self-delete");
        assertThat(accountRepository.findById(admin.getId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void anAdminCanDemoteAndDeleteAnotherAdminWhileAnotherAdminRemains() throws Exception {
        Account admin = account(Role.ADMIN);
        Account second = account(Role.ADMIN);
        Account third = account(Role.ADMIN);

        assertThat(perform(admin, put("/api/v1/admin/accounts/" + second.getId() + "/role"), map("role", "USER"))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(perform(admin, delete("/api/v1/admin/accounts/" + third.getId()), null)
                .getResponse().getStatus()).isEqualTo(204);

        assertThat(accountRepository.findById(second.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
        assertThat(accountRepository.findById(third.getId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.DEACTIVATED);
    }

    @Test
    void theLastActiveAdminCanBeNeitherDemotedNorDeleted() {
        Account lastAdmin = account(Role.ADMIN);
        List<Long> deactivated = deactivateOtherAdmins(lastAdmin);
        try {
            // An actor that is not the target (the guard, not the self rule, must stop this).
            AuthenticatedUser actor = new AuthenticatedUser(Long.MAX_VALUE, "synthetic-admin", Role.ADMIN);
            authenticateAs(actor);

            assertThatThrownBy(() -> accountAdminService.changeRole(actor, lastAdmin.getId(), Role.USER))
                    .isInstanceOfSatisfying(ApiProblemException.class,
                            e -> assertThat(e.code()).isEqualTo("account/last-admin"));
            assertThatThrownBy(() -> accountAdminService.softDelete(actor, lastAdmin.getId()))
                    .isInstanceOfSatisfying(ApiProblemException.class,
                            e -> assertThat(e.code()).isEqualTo("account/last-admin"));

            Account stored = accountRepository.findById(lastAdmin.getId()).orElseThrow();
            assertThat(stored.getRole()).isEqualTo(Role.ADMIN);
            assertThat(stored.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        } finally {
            reactivate(deactivated);
        }
    }

    @Test
    void twoAdminsDemotingEachOtherConcurrentlyLeaveOneAdmin() throws Exception {
        for (int round = 0; round < 3; round++) {
            Account a = account(Role.ADMIN);
            Account b = account(Role.ADMIN);
            List<Long> deactivated = deactivateOtherAdmins(a, b);
            try {
                List<Integer> statuses = concurrently(
                        () -> status(a, put("/api/v1/admin/accounts/" + b.getId() + "/role"), map("role", "USER")),
                        () -> status(b, put("/api/v1/admin/accounts/" + a.getId() + "/role"), map("role", "USER")));

                assertOneSucceededAndTheOtherWasRefused(statuses, 200);
                assertThat(activeAdminsAmong(a, b)).isEqualTo(1);
            } finally {
                reactivate(deactivated);
            }
        }
    }

    @Test
    void twoAdminsDeletingEachOtherConcurrentlyLeaveOneAdmin() throws Exception {
        for (int round = 0; round < 3; round++) {
            Account a = account(Role.ADMIN);
            Account b = account(Role.ADMIN);
            List<Long> deactivated = deactivateOtherAdmins(a, b);
            try {
                List<Integer> statuses = concurrently(
                        () -> status(a, delete("/api/v1/admin/accounts/" + b.getId()), null),
                        () -> status(b, delete("/api/v1/admin/accounts/" + a.getId()), null));

                assertOneSucceededAndTheOtherWasRefused(statuses, 204);
                assertThat(activeAdminsAmong(a, b)).isEqualTo(1);
            } finally {
                reactivate(deactivated);
            }
        }
    }

    /**
     * The loser either reached the guard while still an ADMIN (409 last-admin) or was already demoted/deleted
     * when its request was authenticated (403 / 401).
     */
    private static void assertOneSucceededAndTheOtherWasRefused(List<Integer> statuses, int success) {
        assertThat(statuses).as("statuses %s", statuses).containsOnlyOnce(success);
        assertThat(statuses.stream().filter(status -> status != success).toList())
                .as("statuses %s", statuses).hasSize(1).allMatch(status -> status == 409 || status == 403
                        || status == 401);
    }

    private int status(Account caller, MockHttpServletRequestBuilder request, Object body) throws Exception {
        return perform(caller, request, body).getResponse().getStatus();
    }

    private long activeAdminsAmong(Account... accounts) {
        long count = 0;
        for (Account account : accounts) {
            Account stored = accountRepository.findById(account.getId()).orElseThrow();
            if (stored.getRole() == Role.ADMIN && stored.getStatus() == AccountStatus.ACTIVE) {
                count++;
            }
        }
        return count;
    }

    private List<Long> deactivateOtherAdmins(Account... keep) {
        List<Long> keepIds = java.util.Arrays.stream(keep).map(Account::getId).toList();
        List<Long> others = jdbc.queryForList("SELECT id FROM account WHERE role = 'ADMIN' AND status = 'ACTIVE'",
                Long.class).stream().filter(id -> !keepIds.contains(id)).toList();
        others.forEach(id -> jdbc.update("UPDATE account SET status = 'DEACTIVATED' WHERE id = ?", id));
        return others;
    }

    private void reactivate(List<Long> ids) {
        ids.forEach(id -> jdbc.update("UPDATE account SET status = 'ACTIVE' WHERE id = ?", id));
    }

    @SafeVarargs
    private static List<Integer> concurrently(Callable<Integer>... tasks) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(tasks.length);
        ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(executor.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            executor.shutdownNow();
        }
    }
}
