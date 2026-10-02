package com.educore.authz;

import com.educore.account.AccountAdminService;
import com.educore.account.ProfileService;
import com.educore.account.UpdateProfileRequest;
import com.educore.common.web.ApiProblemException;
import com.educore.entity.Account;
import com.educore.entity.AccountStatus;
import com.educore.entity.Role;
import com.educore.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A self-service profile edit racing an ADMIN demotion or soft delete never undoes the ADMIN's change.
 * <p>
 * Each test is ordered deterministically: the first transaction performs its write and holds it open; the
 * second request is started and the test waits until PostgreSQL reports it blocked on a row lock
 * ({@code pg_stat_activity.wait_event_type = 'Lock'}); only then is the first transaction committed.
 */
class ConcurrentAccountUpdateIT extends AuthzIntegrationSupport {

    @Autowired
    private ProfileService profileService;

    @Autowired
    private AccountAdminService accountAdminService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void demotionWaitingBehindAProfileEditIsApplied() throws Exception {
        Account actor = account(Role.ADMIN);
        Account target = account(Role.ADMIN);

        Ordered run = firstHoldsThenSecond(
                () -> profileService.update(AuthenticatedUser.of(target), names()), target,
                () -> accountAdminService.changeRole(AuthenticatedUser.of(actor), target.getId(), Role.USER), actor);

        run.first().get(30, TimeUnit.SECONDS);
        run.second().get(30, TimeUnit.SECONDS);
        Account stored = reload(target);
        assertThat(stored.getRole()).isEqualTo(Role.USER);
        assertThat(stored.getFirstName()).isEqualTo("Edited");
    }

    @Test
    void profileEditWaitingBehindADemotionDoesNotRestoreTheOldRole() throws Exception {
        Account actor = account(Role.ADMIN);
        Account target = account(Role.ADMIN);

        Ordered run = firstHoldsThenSecond(
                () -> accountAdminService.changeRole(AuthenticatedUser.of(actor), target.getId(), Role.USER), actor,
                () -> profileService.update(AuthenticatedUser.of(target), names()), target);

        run.first().get(30, TimeUnit.SECONDS);
        run.second().get(30, TimeUnit.SECONDS);
        Account stored = reload(target);
        assertThat(stored.getRole()).isEqualTo(Role.USER);
        assertThat(stored.getFirstName()).isEqualTo("Edited");
    }

    @Test
    void softDeleteWaitingBehindAProfileEditIsApplied() throws Exception {
        Account actor = account(Role.ADMIN);
        Account target = account(Role.USER);

        Ordered run = firstHoldsThenSecond(
                () -> profileService.update(AuthenticatedUser.of(target), names()), target,
                () -> {
                    accountAdminService.softDelete(AuthenticatedUser.of(actor), target.getId());
                    return null;
                }, actor);

        run.first().get(30, TimeUnit.SECONDS);
        run.second().get(30, TimeUnit.SECONDS);
        Account stored = reload(target);
        assertThat(stored.getStatus()).isEqualTo(AccountStatus.DEACTIVATED);
        assertThat(stored.getFirstName()).isEqualTo("Edited");
    }

    @Test
    void profileEditWaitingBehindASoftDeleteDoesNotRestoreTheAccount() throws Exception {
        Account actor = account(Role.ADMIN);
        Account target = account(Role.USER);

        Ordered run = firstHoldsThenSecond(
                () -> {
                    accountAdminService.softDelete(AuthenticatedUser.of(actor), target.getId());
                    return null;
                }, actor,
                () -> profileService.update(AuthenticatedUser.of(target), names()), target);

        run.first().get(30, TimeUnit.SECONDS);
        // The edit re-checks status = ACTIVE after the lock is released and updates nothing.
        assertThatThrownBy(() -> run.second().get(30, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ApiProblemException.class)
                .satisfies(e -> assertThat(((ApiProblemException) e.getCause()).code())
                        .isEqualTo("account/not-found"));
        Account stored = reload(target);
        assertThat(stored.getStatus()).isEqualTo(AccountStatus.DEACTIVATED);
        assertThat(stored.getFirstName()).isEqualTo(target.getFirstName());
    }

    @Test
    void aWriteBasedOnAStaleReadIsRejectedByTheVersion() throws Exception {
        Account actor = account(Role.ADMIN);
        Account target = account(Role.ADMIN);
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch demoted = new CountDownLatch(1);

        Future<Object> staleWriter = executor.submit(() -> new TransactionTemplate(transactionManager).execute(tx -> {
            Account stale = accountRepository.findById(target.getId()).orElseThrow();
            read.countDown();
            await(demoted);
            stale.setFirstName("Stale");
            accountRepository.saveAndFlush(stale);
            return null;
        }));
        assertThat(read.await(30, TimeUnit.SECONDS)).isTrue();
        asUser(actor, () -> accountAdminService.changeRole(AuthenticatedUser.of(actor), target.getId(), Role.USER));
        demoted.countDown();

        assertThatThrownBy(() -> staleWriter.get(30, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
        Account stored = reload(target);
        assertThat(stored.getRole()).isEqualTo(Role.USER);
        assertThat(stored.getFirstName()).isEqualTo(target.getFirstName());
        assertThat(stored.getVersion()).isEqualTo(target.getVersion() + 1);
    }

    // ---- ordering machinery -----------------------------------------------------------------------------

    record Ordered(Future<Object> first, Future<Object> second) {
    }

    /**
     * Runs {@code first} in a transaction that stays open after its work, starts {@code second}, waits until
     * the second one is blocked on a lock, then commits the first.
     */
    private Ordered firstHoldsThenSecond(Callable<Object> first, Account firstCaller, Callable<Object> second,
                                         Account secondCaller) throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        long waitingBefore = lockWaiters();
        Future<Object> firstRun = executor.submit(() -> asUser(firstCaller,
                () -> new TransactionTemplate(transactionManager).execute(tx -> {
                    try {
                        Object result = first.call();
                        written.countDown();
                        await(release);
                        return result;
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })));
        assertThat(written.await(30, TimeUnit.SECONDS)).as("first write done").isTrue();
        Future<Object> secondRun = executor.submit(() -> asUser(secondCaller, second));
        awaitLockWaiters(waitingBefore + 1);
        release.countDown();
        return new Ordered(firstRun, secondRun);
    }

    private long lockWaiters() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                + "WHERE datname = current_database() AND wait_event_type = 'Lock'", Long.class);
        return count == null ? 0 : count;
    }

    private void awaitLockWaiters(long expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (lockWaiters() < expected) {
            assertThat(Instant.now()).as("second request blocked on the row lock").isBefore(deadline);
            Thread.sleep(20);
        }
    }

    private static <T> T asUser(Account caller, Callable<T> work) throws Exception {
        authenticateAs(AuthenticatedUser.of(caller));
        try {
            return work.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static UpdateProfileRequest names() {
        return new UpdateProfileRequest("Edited", "Name");
    }

    private Account reload(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow();
    }
}
