package com.educore.publicapi;

import com.educore.entity.Account;
import com.educore.entity.Course;
import com.educore.entity.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Concurrent course writes, each ordered deterministically: a first transaction performs its write and stays
 * open, the competing request is started, the test waits until PostgreSQL reports it blocked on a lock
 * ({@code pg_stat_activity.wait_event_type = 'Lock'}), and only then is the first transaction committed.
 * <ul>
 *   <li>An ADMIN edit that read the course before a concurrent unpublish must not republish it
 *       ({@code Course.version}, 409 {@code request/concurrent-modification}).</li>
 *   <li>A generated slug that collides with a concurrently inserted one is generated again
 *       ({@code stem-2}); an explicit slug that collides is 409 {@code course/slug-taken}, never a generic
 *       conflict.</li>
 * </ul>
 */
class CourseConcurrencyIT extends PublicApiTestSupport {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void aStaleCourseWriteIsRejectedByTheVersion() throws Exception {
        Account admin = account(Role.ADMIN);
        Course course = publishedCourse(uniqueSlug("stale"), "Published.");
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch unpublished = new CountDownLatch(1);

        Future<Object> staleWriter = executor.submit(() -> new TransactionTemplate(transactionManager).execute(tx -> {
            Course stale = courseRepository.findById(course.getId()).orElseThrow();
            read.countDown();
            await(unpublished);
            stale.setDescription("Stale edit.");
            courseRepository.saveAndFlush(stale);
            return null;
        }));
        assertThat(read.await(30, TimeUnit.SECONDS)).isTrue();
        MvcResult unpublish = perform(admin, put("/api/v1/admin/courses/" + course.getId()),
                map("name", course.getName(), "published", false));
        assertThat(unpublish.getResponse().getStatus()).isEqualTo(200);
        unpublished.countDown();

        assertThatThrownBy(() -> staleWriter.get(30, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
        Course stored = courseRepository.findById(course.getId()).orElseThrow();
        assertThat(stored.isPublished()).isFalse();
        assertThat(stored.getDescription()).isEqualTo("Published.");
    }

    @Test
    void anEditWaitingBehindAnUnpublishDoesNotRepublishTheCourse() throws Exception {
        Account admin = account(Role.ADMIN);
        Course course = publishedCourse(uniqueSlug("race"), "Published.");

        Future<MvcResult> edit = firstHoldsThenSecond(
                () -> jdbc.update("UPDATE course SET published = false, version = version + 1, updated_at = now() "
                        + "WHERE id = ?", course.getId()),
                () -> perform(admin, put("/api/v1/admin/courses/" + course.getId()),
                        map("name", course.getName(), "term", "2027/2", "instructor", "Instructor Race")));

        MvcResult result = edit.get(30, TimeUnit.SECONDS);
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(result).get("code").asText()).isEqualTo("request/concurrent-modification");
        Course stored = courseRepository.findById(course.getId()).orElseThrow();
        assertThat(stored.isPublished()).isFalse();
        assertThat(stored.getTerm()).isEqualTo("2026/1");
    }

    @Test
    void aGeneratedSlugThatLosesARaceIsGeneratedAgain() throws Exception {
        Account admin = account(Role.ADMIN);
        String marker = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String stem = "race-" + marker;
        String competitor = "Competitor " + marker;
        String name = "Race " + marker;
        cleanUpCourseName(competitor);
        cleanUpCourseName(name);

        Future<MvcResult> create = firstHoldsThenSecond(
                () -> insertUnpublished(competitor, stem),
                () -> perform(admin, post("/api/v1/admin/courses"), map("name", name, "term", "2026/1")));

        MvcResult result = create.get(30, TimeUnit.SECONDS);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(result).get("slug").asText()).isEqualTo(stem + "-2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM security_event WHERE type = 'COURSE_CHANGED' "
                + "AND details ->> 'courseId' = ?", Integer.class, body(result).get("id").asText())).isEqualTo(1);
    }

    @Test
    void anExplicitSlugThatLosesARaceIsSlugTaken() throws Exception {
        Account admin = account(Role.ADMIN);
        String marker = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String slug = "explicit-" + marker;
        String competitor = "Competitor " + marker;
        String name = "Explicit " + marker;
        cleanUpCourseName(competitor);
        cleanUpCourseName(name);

        Future<MvcResult> create = firstHoldsThenSecond(
                () -> insertUnpublished(competitor, slug),
                () -> perform(admin, post("/api/v1/admin/courses"), map("name", name, "slug", slug)));

        MvcResult result = create.get(30, TimeUnit.SECONDS);
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(result).get("code").asText()).isEqualTo("course/slug-taken");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM course WHERE name = ?", Integer.class, name)).isZero();
    }

    // ---- ordering machinery -----------------------------------------------------------------------------

    private int insertUnpublished(String name, String slug) {
        return jdbc.update("INSERT INTO course (name, term, instructor, slug, published) VALUES (?, '2026/1', ?, ?, false)",
                name, "Instructor Competitor", slug);
    }

    /**
     * Runs {@code first} in a transaction that stays open after its write, starts {@code second}, waits until
     * the second one is blocked on a lock, then commits the first.
     */
    private Future<MvcResult> firstHoldsThenSecond(Callable<Object> first, Callable<MvcResult> second)
            throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        long waitingBefore = lockWaiters();
        Future<Object> firstRun = executor.submit(() -> new TransactionTemplate(transactionManager).execute(tx -> {
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
        }));
        assertThat(written.await(30, TimeUnit.SECONDS)).as("first write done").isTrue();
        Future<MvcResult> secondRun = executor.submit(second);
        awaitLockWaiters(waitingBefore + 1);
        release.countDown();
        firstRun.get(30, TimeUnit.SECONDS);
        return secondRun;
    }

    private long lockWaiters() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                + "WHERE datname = current_database() AND wait_event_type = 'Lock'", Long.class);
        return count == null ? 0 : count;
    }

    private void awaitLockWaiters(long expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (lockWaiters() < expected) {
            assertThat(Instant.now()).as("second request blocked on a lock").isBefore(deadline);
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
