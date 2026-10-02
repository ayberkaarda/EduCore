package com.educore.lifecycle;

import com.educore.entity.Account;
import com.educore.entity.Role;
import com.educore.repository.AccountRepository;
import com.educore.support.ScratchDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.Container;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-09 restore drill with the real application: back up, purge an account and log another one out, restore the
 * OLD dump (which resurrects both), then
 * <ul>
 *   <li>with the post-restore SQL of {@code scripts/backup/post-restore.sh} and a restart: the backend replays the
 *       erasure ledger file (a volume outside the dump) before serving, so the purged account is gone again and
 *       the logged-out refresh cookie stays dead;</li>
 *   <li>without the post-restore step: the backend notices that the ledger file is ahead of the database and does
 *       the same on its own.</li>
 * </ul>
 * Each test uses its own scratch database and a fixed pepper (the ledger digests must match across restarts).
 */
class ErasureLedgerRestoreIT {

    /** TEST DATA ONLY: a fixed login pepper of the required length, so digests survive the restart. */
    private static final String PEPPER = "restore-drill-test-only-pepper-0123456789";
    private static final String PASSWORD = "restore-drill-test-only-password";
    private static final String ORIGIN = "http://localhost:3000";

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();

    private record Fixture(ScratchDatabase db, String[] args, long purgedId, String purgedUsername,
                           String keptUsername, String keptCookie) {
    }

    @Test
    void postRestoreAndTheStartupReplayKeepAPurgedAccountGoneAndARevokedSessionDead() throws Exception {
        Fixture fixture = backUpThenPurgeAndLogOut();

        restoreTheOldDump(fixture);
        fixture.db().execute(Files.readString(Path.of("scripts/backup/post-restore.sql"), StandardCharsets.UTF_8));

        try (ConfigurableApplicationContext restarted = fixture.db().start("test", fixture.args())) {
            JdbcTemplate jdbc = restarted.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE id = ?", Integer.class,
                    fixture.purgedId())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM security_event WHERE type = 'ACCOUNT_PURGED' "
                    + "AND details ->> 'trigger' = 'LEDGER_REPLAY'", Integer.class)).isOne();
            Map<String, Object> replay = jdbc.queryForMap("SELECT source, completed_at, accounts_purged "
                    + "FROM restore_replay");
            assertThat(replay.get("source")).isEqualTo("POST_RESTORE");
            assertThat(replay.get("completed_at")).isNotNull();
            assertThat(((Number) replay.get("accounts_purged")).intValue()).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL",
                    Integer.class)).isZero();

            int port = port(restarted);
            assertThat(refresh(port, fixture.keptCookie()).statusCode()).as("logged-out cookie after restore")
                    .isEqualTo(401);
            assertThat(login(port, fixture.purgedUsername()).statusCode()).as("purged account after restore")
                    .isEqualTo(401);
            assertThat(login(port, fixture.keptUsername()).statusCode()).as("service works after restore")
                    .isEqualTo(200);
        }
    }

    @Test
    void withoutThePostRestoreStepTheBackendDetectsTheLedgerAheadAndReplaysOnItsOwn() throws Exception {
        Fixture fixture = backUpThenPurgeAndLogOut();

        restoreTheOldDump(fixture);

        try (ConfigurableApplicationContext restarted = fixture.db().start("test", fixture.args())) {
            JdbcTemplate jdbc = restarted.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE id = ?", Integer.class,
                    fixture.purgedId())).isZero();
            assertThat(jdbc.queryForObject("SELECT source FROM restore_replay", String.class))
                    .isEqualTo("LEDGER_FILE_AHEAD");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM erasure_ledger", Integer.class)).isOne();
            assertThat(refresh(port(restarted), fixture.keptCookie()).statusCode()).isEqualTo(401);
        }
    }

    /** Two accounts with sessions, a dump, then: account A purged (ledger line), account B logged out. */
    private Fixture backUpThenPurgeAndLogOut() throws Exception {
        ScratchDatabase db = ScratchDatabase.create();
        Path ledgerFile = dir.resolve("ledger-" + db.name()).resolve("erasure-ledger.log");
        String[] args = {"--educore.lifecycle.erasure-ledger-file=" + ledgerFile,
                "--educore.security.login.username-pepper=" + PEPPER};
        String purgedUsername = "drill-a-" + UUID.randomUUID();
        String keptUsername = "drill-b-" + UUID.randomUUID();
        long purgedId;
        String keptCookie;
        try (ConfigurableApplicationContext app = db.start("test", args)) {
            AccountRepository accounts = app.getBean(AccountRepository.class);
            PasswordEncoder encoder = app.getBean(PasswordEncoder.class);
            purgedId = accounts.save(Account.builder().username(purgedUsername).password(encoder.encode(PASSWORD))
                    .firstName("Drill").lastName("Erased").studentNumber("98" + System.nanoTime() % 1_000_000)
                    .role(Role.USER).build()).getId();
            accounts.save(Account.builder().username(keptUsername).password(encoder.encode(PASSWORD))
                    .firstName("Drill").lastName("Kept").role(Role.USER).build());
            int port = port(app);
            assertThat(login(port, purgedUsername).statusCode()).isEqualTo(200);
            HttpResponse<String> kept = login(port, keptUsername);
            assertThat(kept.statusCode()).isEqualTo(200);
            keptCookie = refreshCookie(kept);

            dump(db);

            AccountPurger purger = app.getBean(AccountPurger.class);
            new TransactionTemplate(app.getBean(PlatformTransactionManager.class))
                    .execute(status -> purger.purge(purgedId, AccountPurger.Trigger.ADMIN_HARD_DELETE));
            assertThat(post(port, "/api/v1/auth/logout", keptCookie, null).statusCode()).isEqualTo(204);
            assertThat(refresh(port, keptCookie).statusCode()).isEqualTo(401);
        }
        List<String> ledger = Files.readAllLines(ledgerFile, StandardCharsets.US_ASCII);
        assertThat(ledger).singleElement().satisfies(line -> assertThat(line).startsWith("v1 ")
                .doesNotContain(purgedUsername).matches("v1 \\S+ [0-9a-f]{64} [0-9a-f]{64} [0-9a-f]{64}"));
        return new Fixture(db, args, purgedId, purgedUsername, keptUsername, keptCookie);
    }

    private static void dump(ScratchDatabase db) throws Exception {
        exec(db, "pg_dump", "-U", db.username(), "-d", db.name(), "--format=custom", "-f", "/tmp/" + db.name() + ".dump");
    }

    /** What scripts/backup/restore.sh runs: the whole dump in one transaction over the current schema. */
    private static void restoreTheOldDump(Fixture fixture) throws Exception {
        ScratchDatabase db = fixture.db();
        exec(db, "pg_restore", "-U", db.username(), "-d", db.name(), "--clean", "--if-exists", "--no-owner", "--no-acl",
                "--single-transaction", "--exit-on-error", "/tmp/" + db.name() + ".dump");
        try (var connection = db.connect(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM account WHERE id = " + fixture.purgedId())) {
            rows.next();
            assertThat(rows.getInt(1)).as("the old dump resurrects the purged account").isOne();
        }
        try (var connection = db.connect(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL")) {
            rows.next();
            assertThat(rows.getInt(1)).as("the old dump revives the revoked sessions").isEqualTo(2);
        }
    }

    private static void exec(ScratchDatabase db, String... command) throws Exception {
        Container.ExecResult result = db.exec(command);
        assertThat(result.getExitCode()).as(command[0] + ": " + result.getStderr()).isZero();
    }

    private static int port(ConfigurableApplicationContext context) {
        return Integer.parseInt(context.getEnvironment().getProperty("local.server.port", "0"));
    }

    private HttpResponse<String> login(int port, String username) throws Exception {
        return post(port, "/api/v1/auth/login", null,
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
    }

    private HttpResponse<String> refresh(int port, String cookie) throws Exception {
        return post(port, "/api/v1/auth/refresh", cookie, null);
    }

    private HttpResponse<String> post(int port, String path, String cookie, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Origin", ORIGIN)
                .POST(json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        if (cookie != null) {
            request.header("Cookie", "educore_rt=" + cookie);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String refreshCookie(HttpResponse<String> response) {
        String header = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("educore_rt=")).findFirst().orElseThrow();
        return header.substring("educore_rt=".length(), header.indexOf(';'));
    }
}
