package com.educore.course;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * The revision of the public catalog ({@code catalog_revision}, {@code V42__catalog_revision.sql}): when the
 * set or content of published courses last changed. {@link CourseService} calls {@link #advance()} inside
 * the transaction of every change that a visitor can see (a published course created, changed, unpublished
 * or deleted, or a course published), so the value moves forward after an unpublish or delete too.
 */
@Component
public class CatalogRevision {

    private static final String ADVANCE =
            "UPDATE catalog_revision SET revised_at = greatest(revised_at, now()) WHERE id = 1";

    /**
     * The revision, but never older than the newest published course (a published row written outside
     * {@link CourseService} still moves it forward); empty while no course is published.
     */
    private static final String CURRENT = "SELECT greatest(r.revised_at, p.latest) FROM catalog_revision r "
            + "CROSS JOIN (SELECT max(updated_at) AS latest FROM course WHERE published) p "
            + "WHERE r.id = 1 AND p.latest IS NOT NULL";

    private final JdbcTemplate jdbc;

    public CatalogRevision(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Moves the revision to the current transaction time; never moves it backwards. */
    public void advance() {
        jdbc.update(ADVANCE);
    }

    /** The current revision, or empty while no course is published. */
    public Optional<Instant> current() {
        return jdbc.query(CURRENT, (rs, row) -> rs.getTimestamp(1)).stream().findFirst().map(Timestamp::toInstant);
    }
}
