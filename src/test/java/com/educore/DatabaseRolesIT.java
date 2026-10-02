package com.educore;

import com.educore.lifecycle.AccountPurger;
import com.educore.support.ScratchDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-15: in {@code prod} the application's connection pool is the least-privilege runtime role created by
 * {@code infra/postgres/app-role.sql} (the script {@code infra/postgres/init/01-roles.sh} runs on a new database),
 * while Flyway migrates as the owner. The application works as that role (migrations, bootstrap ADMIN, a purge with
 * its audit, ledger and delivery clean-up) and a SQL foothold through its own pool can neither change the schema,
 * install an extension, run a program on the server, read server files nor read password hashes of roles. Before
 * the fix the pool used the owner, a superuser in the compose image, and every statement below succeeded.
 */
class DatabaseRolesIT {

    private static final List<String> FORBIDDEN = List.of(
            "DROP TABLE enrollments",
            "ALTER TABLE account ADD COLUMN intruder integer",
            "CREATE TABLE intruder (id integer)",
            "TRUNCATE security_event",
            "CREATE EXTENSION pgcrypto",
            "COPY (SELECT 1) TO PROGRAM 'id'",
            "SELECT pg_read_file('/etc/passwd')",
            "SELECT rolpassword FROM pg_authid",
            "CREATE ROLE intruder LOGIN SUPERUSER");

    @Test
    void prodConnectsAsTheRuntimeRoleThatCanOnlyReadAndWriteRows() throws Exception {
        ScratchDatabase db = ScratchDatabase.create();
        try (ConfigurableApplicationContext prod = db.start("prod")) {
            JdbcTemplate jdbc = prod.getBean(JdbcTemplate.class);

            assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(db.runtimeRole());
            assertThat(jdbc.queryForObject("SELECT rolsuper OR rolcreaterole OR rolcreatedb FROM pg_roles "
                    + "WHERE rolname = current_user", Boolean.class)).isFalse();
            assertThat(jdbc.queryForList("SELECT DISTINCT installed_by FROM flyway_schema_history", String.class))
                    .containsExactly(db.username());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE username = 'scratch-admin'",
                    Integer.class)).isOne();

            // Normal work as the runtime role: insert an account and purge it (deletes, audit, erasure ledger).
            long id = jdbc.queryForObject("INSERT INTO account (username, password, role, status, student_number) "
                    + "VALUES ('roles-it-user', 'roles-it-hash', 'USER', 'ACTIVE', '97900001') RETURNING id",
                    Long.class);
            AccountPurger purger = prod.getBean(AccountPurger.class);
            AccountPurger.Result purged = new TransactionTemplate(prod.getBean(PlatformTransactionManager.class))
                    .execute(status -> purger.purge(id, AccountPurger.Trigger.ADMIN_HARD_DELETE));
            assertThat(purged).isNotNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM erasure_ledger", Integer.class)).isOne();
            Path ledger = Path.of("target/test-erasure-ledger/" + db.name() + ".log");
            assertThat(Files.readAllLines(ledger, StandardCharsets.US_ASCII)).hasSize(1);

            for (String statement : FORBIDDEN) {
                assertThatThrownBy(() -> jdbc.execute(statement)).as(statement).rootCause()
                        .hasMessageMatching("(?s).*(permission denied|must be owner|must be superuser"
                                + "|must have privileges|only roles with).*");
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM enrollments", Integer.class)).isNotNull();
        }
    }
}
