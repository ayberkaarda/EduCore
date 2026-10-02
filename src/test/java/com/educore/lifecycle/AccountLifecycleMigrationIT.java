package com.educore.lifecycle;

import com.educore.support.ScratchDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code V21__account_lifecycle.sql} on a database that already holds accounts: {@code deleted = 0} becomes
 * {@code ACTIVE}, any other value {@code DEACTIVATED}; the optimistic-lock version is kept, the {@code deleted}
 * column is gone, and the new constraints reject inconsistent lifecycle rows and malformed pseudonyms.
 */
class AccountLifecycleMigrationIT {

    private static Flyway flyway(SingleConnectionDataSource dataSource, String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
    }

    @Test
    void migratesTheDeletedFlagToTheLifecycleStatus() throws Exception {
        ScratchDatabase db = ScratchDatabase.create();
        try (Connection connection = db.connect()) {
            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
            flyway(dataSource, "20").migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.update("INSERT INTO account (username, password, role, deleted, version) VALUES "
                    + "('active-user', 'x', 'USER', 0, 3), ('deleted-user', 'x', 'USER', 1, 7), "
                    + "('deleted-admin', 'x', 'ADMIN', 1, 0), ('odd-flag', 'x', 'USER', 2, 1)");
            jdbc.update("INSERT INTO security_event (type, actor_account_id, target_account_id, at) "
                    + "VALUES ('ACCOUNT_DELETED', 1, 2, now())");

            flyway(dataSource, "21").migrate();

            List<Map<String, Object>> rows = jdbc.queryForList("SELECT username, status, version, deleted_at, "
                    + "delete_after FROM account ORDER BY username");
            assertThat(rows).extracting(row -> row.get("username") + "=" + row.get("status") + "/v" + row.get("version"))
                    .containsExactly("active-user=ACTIVE/v3", "deleted-admin=DEACTIVATED/v0",
                            "deleted-user=DEACTIVATED/v7", "odd-flag=DEACTIVATED/v1");
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.get("deleted_at")).isNull();
                assertThat(row.get("delete_after")).isNull();
            });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_name = 'account' AND column_name = 'deleted'", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns "
                    + "WHERE table_name = 'account' AND column_name = 'status'", String.class)).contains("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT actor_pseudonym IS NULL AND target_pseudonym IS NULL "
                    + "FROM security_event", Boolean.class)).isTrue();

            jdbc.update("INSERT INTO account (username, password, role) VALUES ('new-user', 'x', 'USER')");
            assertThat(jdbc.queryForObject("SELECT status FROM account WHERE username = 'new-user'", String.class))
                    .isEqualTo("ACTIVE");
            jdbc.update("UPDATE account SET status = 'PENDING_DELETION', deleted_at = now(), "
                    + "delete_after = now() + interval '30 days' WHERE username = 'new-user'");

            assertThatThrownBy(() -> jdbc.update("UPDATE account SET status = 'GONE' WHERE username = 'active-user'"))
                    .hasMessageContaining("ck_account_");
            assertThatThrownBy(() -> jdbc.update("UPDATE account SET delete_after = now() WHERE username = 'active-user'"))
                    .hasMessageContaining("ck_account_lifecycle_dates");
            assertThatThrownBy(() -> jdbc.update("UPDATE account SET status = 'PENDING_DELETION' "
                    + "WHERE username = 'active-user'")).hasMessageContaining("ck_account_lifecycle_dates");
            assertThatThrownBy(() -> jdbc.update("UPDATE security_event SET actor_pseudonym = 'purged:0123456789abcdef'"))
                    .hasMessageContaining("ck_security_event_actor_ref");
            assertThatThrownBy(() -> jdbc.update("UPDATE security_event SET target_account_id = NULL, "
                    + "target_pseudonym = 'purged:XYZ'")).hasMessageContaining("ck_security_event_target_pseudonym");
            jdbc.update("UPDATE security_event SET target_account_id = NULL, target_pseudonym = 'purged:0123456789abcdef'");
        }
    }
}
