package com.educore;

import com.educore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The migrated schema contains every expected table, column and constraint. */
class FlywayMigrationIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void expectedTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

        assertThat(tables).contains(
                "flyway_schema_history",
                "account", "course", "enrollments", "ip_allocation_range", "ip_deny_rule", "job_log",
                "batch_job_instance", "batch_job_execution", "batch_job_execution_params",
                "batch_step_execution", "batch_step_execution_context", "batch_job_execution_context");
    }

    @Test
    void batchSequencesExist() {
        List<String> sequences = jdbc.queryForList(
                "SELECT sequence_name FROM information_schema.sequences WHERE sequence_schema = 'public'",
                String.class);

        assertThat(sequences).contains("batch_job_seq", "batch_job_execution_seq", "batch_step_execution_seq");
    }

    @Test
    void mustChangePasswordColumnDefaultsToFalse() {
        String nullable = jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'account' AND column_name = 'must_change_password'", String.class);
        Integer flagged = jdbc.queryForObject(
                "SELECT count(*) FROM account WHERE must_change_password", Integer.class);

        assertThat(nullable).isEqualTo("NO");
        assertThat(flagged).isZero();
    }

    @Test
    void uniqueConstraintsExist() {
        List<String> constraints = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND constraint_type = 'UNIQUE'", String.class);

        assertThat(constraints).contains(
                "uk_account_username", "uk_account_student_number", "uk_account_ip_address",
                "uk_course_name", "uk_enrollments_account_course");
    }

    @Test
    void devSeedIsSyntheticOnly() {
        List<String> studentNumbers = jdbc.queryForList(
                "SELECT student_number FROM account WHERE student_number IS NOT NULL", String.class);
        Integer courses = jdbc.queryForObject("SELECT count(*) FROM course", Integer.class);

        assertThat(studentNumbers).isNotEmpty()
                .allSatisfy(number -> assertThat(Long.parseLong(number)).isGreaterThanOrEqualTo(9_000_001L));
        assertThat(courses).isEqualTo(4);
    }
}
