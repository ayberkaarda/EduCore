package com.educore.ingestion;

import com.educore.authz.AuthzIntegrationSupport;
import com.educore.entity.JobLog;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Shared context of the ingestion integration tests: a private base directory under the system temp folder,
 * the inbox poller running every 200 ms with a 1 s stability window, small limits (64 KB, 200 rows, 5 skips)
 * and two-row chunks so that several chunks run on the four step threads. Every import is cleaned up
 * (job logs, imported files, accounts, courses, webhook subscriptions).
 */
public abstract class IngestionIntegrationSupport extends AuthzIntegrationSupport {

    static final Path BASE = createBase();
    static final String STUDENT_HEADER = "FirstName,LastName,StudentNumber\n";
    static final String COURSE_HEADER = "name,term,instructor\n";

    @Autowired
    protected IngestionService ingestionService;

    @Autowired
    protected IngestionDirectories directories;

    @Autowired
    protected ImportedFileRepository importedFileRepository;

    @Autowired
    protected JobLogEntryRepository entryRepository;

    private final List<Long> jobLogs = new ArrayList<>();
    private final List<String> fileNameTokens = new ArrayList<>();

    @DynamicPropertySource
    static void ingestionProperties(DynamicPropertyRegistry registry) {
        registry.add("educore.ingestion.base-dir", BASE::toString);
        registry.add("educore.ingestion.poller-enabled", () -> "true");
        registry.add("educore.ingestion.poll-interval", () -> "200ms");
        registry.add("educore.ingestion.stable-after", () -> "1s");
        registry.add("educore.ingestion.max-bytes", () -> "64KB");
        registry.add("educore.ingestion.max-rows", () -> "200");
        registry.add("educore.ingestion.skip-limit", () -> "5");
        registry.add("educore.ingestion.chunk-size", () -> "2");
        registry.add("educore.ingestion.threads", () -> "4");
    }

    private static Path createBase() {
        try {
            return Files.createTempDirectory("educore-ingestion-it-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterEach
    void removeImports() {
        for (String token : fileNameTokens) {
            jobLogs.addAll(jdbc.queryForList("SELECT id FROM job_log WHERE file_name LIKE ?", Long.class,
                    "%" + token + "%"));
        }
        for (Long id : jobLogs) {
            jdbc.update("DELETE FROM imported_file WHERE id = (SELECT imported_file_id FROM job_log WHERE id = ?)", id);
            cleanUpJobLog(id);
        }
        jdbc.update("DELETE FROM webhook_subscription WHERE url LIKE 'https://ingestion-it.example.com/%'");
        jobLogs.clear();
        fileNameTokens.clear();
    }

    /** A unique file name token; job logs of files containing it are removed after the test. */
    protected String token() {
        String token = "t" + UUID.randomUUID().toString().substring(0, 8);
        fileNameTokens.add(token);
        return token;
    }

    /** A student number that is removed (with its account) after the test. */
    protected String number() {
        String number = uniqueStudentNumber();
        cleanUpStudentNumber(number);
        return number;
    }

    /** A course name that is removed (with its course) after the test. */
    protected String courseName(String prefix) {
        String name = prefix + "-" + UUID.randomUUID();
        cleanUpCourseName(name);
        return name;
    }

    /** Writes a file outside the watched inbox, ready for a direct {@link IngestionService#ingest} call. */
    protected Path staged(String name, byte[] content) throws IOException {
        Path dir = Files.createDirectories(BASE.resolve("fixtures"));
        Path file = dir.resolve(name);
        Files.write(file, content);
        return file;
    }

    protected Path staged(String name, String content) throws IOException {
        return staged(name, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Imports a staged file synchronously and returns its closed job log. */
    protected JobLog importNow(Path staged) {
        long id = ingestionService.ingest(staged).orElseThrow();
        jobLogs.add(id);
        return jobLogRepository.findById(id).orElseThrow();
    }

    /** Drops a file into the inbox the way a well-behaved client does (temporary name, then atomic rename). */
    protected Path dropIntoInbox(String name, byte[] content) throws IOException {
        Path temporary = directories.inbox().resolve("." + name + ".part");
        Files.write(temporary, content);
        return Files.move(temporary, directories.inbox().resolve(name), StandardCopyOption.ATOMIC_MOVE);
    }

    /** Waits until the poller closed the job log of a file whose name contains {@code token}. */
    protected JobLog awaitClosedJobLog(String token, Duration timeout) {
        return await(() -> jdbc.queryForList(
                        "SELECT id FROM job_log WHERE file_name LIKE ? AND status IS NOT NULL", Long.class,
                        "%" + token + "%").stream().findFirst()
                .flatMap(jobLogRepository::findById), timeout, "closed job log for " + token);
    }

    protected static <T> T await(Supplier<Optional<T>> probe, Duration timeout, String what) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Optional<T> value = probe.get();
            if (value.isPresent()) {
                return value.get();
            }
            pause(Duration.ofMillis(100));
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    protected static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Waits until {@code dir} holds exactly {@code count} files whose name contains {@code token}. The job log is
     * closed before the snapshot is moved (or deleted) and before its report is written, so a test that waited
     * for the closed job log must wait for the files too.
     */
    protected static List<Path> awaitFiles(Path dir, String token, int count, Duration timeout) {
        return await(() -> {
            try {
                List<Path> files = filesContaining(dir, token);
                return files.size() == count ? Optional.of(files) : Optional.empty();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, timeout, count + " file(s) containing " + token + " in " + dir.getFileName());
    }

    /** Files in {@code dir} whose name contains {@code token}. */
    protected static List<Path> filesContaining(Path dir, String token) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> file.getFileName().toString().contains(token)).sorted().toList();
        }
    }
}
