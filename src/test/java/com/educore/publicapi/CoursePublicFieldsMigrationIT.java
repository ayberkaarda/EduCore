package com.educore.publicapi;

import com.educore.course.CourseSlugs;
import com.educore.support.AbstractIntegrationTest;
import com.educore.support.ScratchDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code V40__course_public_fields.sql} on a database that already holds courses: every existing course gets
 * a deterministic, unique, well-formed slug (colliding names get the first free ordinal suffix, never the
 * primary key), stays unpublished, gets an {@code updated_at} and version 0; the new constraints reject a
 * published course without a slug and malformed slugs; {@code V42} creates the single catalog revision row.
 */
class CoursePublicFieldsMigrationIT {

    @Test
    void backfillsUniqueDeterministicSlugsForExistingCourses() throws Exception {
        ScratchDatabase db = ScratchDatabase.create();
        flyway(db, "20").migrate();
        db.execute("""
                INSERT INTO course (id, name, term, instructor) VALUES
                    (1, 'Advanced Web Development', '2026/1', 'A'),
                    (2, 'advanced web-development!', '2026/1', 'B'),
                    (3, 'Çağdaş Türk Şiiri', '2026/1', 'C'),
                    (4, 'Data', NULL, NULL),
                    (5, 'Data 2', NULL, NULL),
                    (6, 'Data!', NULL, NULL),
                    (7, '!!!', NULL, NULL),
                    (8, '???', NULL, NULL),
                    (9, 'A very long course name that keeps going well beyond the fifty character stem limit', NULL, NULL)
                """);

        flyway(db, "42").migrate();

        Map<Long, String> slugs = new LinkedHashMap<>();
        try (Connection connection = db.connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT id, slug, published, updated_at, version FROM course ORDER BY id")) {
            while (rows.next()) {
                assertThat(rows.getLong("version")).isZero();
                slugs.put(rows.getLong("id"), rows.getString("slug"));
                assertThat(rows.getBoolean("published")).isFalse();
                assertThat(rows.getTimestamp("updated_at")).isNotNull();
            }
        }
        assertThat(slugs).containsExactly(
                Map.entry(1L, "advanced-web-development"),
                Map.entry(2L, "advanced-web-development-2"),
                Map.entry(3L, "cagdas-turk-siiri"),
                Map.entry(4L, "data"),
                Map.entry(5L, "data-2"),
                Map.entry(6L, "data-3"),
                Map.entry(7L, "course"),
                Map.entry(8L, "course-2"),
                Map.entry(9L, "a-very-long-course-name-that-keeps-going-well-beyo"));
        assertThat(slugs.values()).doesNotHaveDuplicates()
                .allSatisfy(slug -> assertThat(slug).matches(CourseSlugs.PATTERN).hasSizeLessThanOrEqualTo(80));
        try (Connection connection = db.connect(); Statement statement = connection.createStatement();
             ResultSet revision = statement.executeQuery("SELECT count(*), min(id) FROM catalog_revision")) {
            assertThat(revision.next()).isTrue();
            assertThat(revision.getInt(1)).isEqualTo(1);
            assertThat(revision.getInt(2)).isEqualTo(1);
        }
        assertThatThrownBy(() -> db.execute("INSERT INTO catalog_revision (id, revised_at) VALUES (2, now())"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_catalog_revision_single_row");
        // The application derives the same stems and ordinals as the migration.
        assertThat(CourseSlugs.unique("Data!", java.util.Set.of("data", "data-2")::contains)).isEqualTo(slugs.get(6L));
        assertThat(CourseSlugs.stem("Çağdaş Türk Şiiri")).isEqualTo("cagdas-turk-siiri");
        assertThat(CourseSlugs.stem("advanced web-development!")).isEqualTo("advanced-web-development");
        assertThat(CourseSlugs.stem("A very long course name that keeps going well beyond the fifty character stem limit"))
                .isEqualTo(slugs.get(9L));
        assertThat(CourseSlugs.stem("!!!")).isEqualTo("course");

        assertThatThrownBy(() -> db.execute("UPDATE course SET published = true, slug = NULL WHERE id = 1"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_course_published_slug");
        assertThatThrownBy(() -> db.execute("UPDATE course SET slug = 'Bad Slug' WHERE id = 1"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_course_slug_format");
        assertThatThrownBy(() -> db.execute("UPDATE course SET slug = 'data' WHERE id = 2"))
                .isInstanceOf(SQLException.class).hasMessageContaining("uk_course_slug");
    }

    private static Flyway flyway(ScratchDatabase db, String target) throws SQLException {
        try (Connection connection = db.connect()) {
            String url = connection.getMetaData().getURL();
            return Flyway.configure()
                    .dataSource(url, Container.username(), Container.password())
                    .locations("classpath:db/migration")
                    .target(target)
                    .load();
        }
    }

    /** Reads the credentials of the shared Testcontainers PostgreSQL (a protected member of the base class). */
    private abstract static class Container extends AbstractIntegrationTest {

        static String username() {
            return POSTGRES.getUsername();
        }

        static String password() {
            return POSTGRES.getPassword();
        }
    }
}
