package com.educore.publicapi;

import java.time.Instant;

/**
 * A published course as the anonymous public API shows it. This record is the complete public shape: it has
 * no id, no account or enrollment data and no publication flag, and it is filled by a JPQL constructor
 * expression ({@link PublicCourseRepository}), so no entity ever reaches the public web layer.
 *
 * @param updatedAt last change of the course (ISO-8601 instant)
 */
public record PublicCourseResponse(String name, String slug, String term, String instructor, String description,
                                   Instant updatedAt) {
}
