package com.educore.auth;

import com.educore.support.ScratchDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V11 creates a refresh_token_family row for every existing family and links tokens to it. */
class RefreshTokenFamilyMigrationIT {

    private static Flyway flyway(SingleConnectionDataSource dataSource, String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
    }

    @Test
    void existingFamiliesAreBackfilled() throws Exception {
        ScratchDatabase db = ScratchDatabase.create();
        try (Connection connection = db.connect()) {
            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
            flyway(dataSource, "10").migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.update("INSERT INTO account (username, password, role, deleted) VALUES ('family-user', 'x', 'USER', 0)");
            long accountId = jdbc.queryForObject("SELECT id FROM account WHERE username = 'family-user'", Long.class);
            UUID live = UUID.randomUUID();
            UUID dead = UUID.randomUUID();
            String insert = "INSERT INTO refresh_token (account_id, token_hash, family_id, issued_at, expires_at, "
                    + "revoked_at) VALUES (?, ?, ?, now() - interval '1 hour', now() + interval '1 day', ?)";
            jdbc.update(insert, accountId, "a".repeat(64), live, java.sql.Timestamp.valueOf("2026-01-01 00:00:00"));
            jdbc.update(insert, accountId, "b".repeat(64), live, null);
            jdbc.update(insert, accountId, "c".repeat(64), dead, java.sql.Timestamp.valueOf("2026-01-02 00:00:00"));

            flyway(dataSource, "11").migrate();

            assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM refresh_token_family WHERE id = ?",
                    Boolean.class, live)).isTrue();
            assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM refresh_token_family WHERE id = ?",
                    Boolean.class, dead)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token_family WHERE account_id = ?",
                    Long.class, accountId)).isEqualTo(2);
            assertThatThrownBy(() -> jdbc.update(insert, accountId, "d".repeat(64), UUID.randomUUID(), null))
                    .hasMessageContaining("fk_refresh_token_family");

            jdbc.update("DELETE FROM account WHERE id = ?", accountId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token_family", Long.class)).isZero();
        }
    }
}
