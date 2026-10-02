package com.educore.publicapi;

import com.educore.entity.Course;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Read-only queries of the public catalog. Every query filters on {@code published = true} and selects the
 * public columns straight into {@link PublicCourseResponse}: the course entity, its id and anything joined to
 * it (enrollments, accounts) are never loaded by this repository. It extends the marker {@link Repository},
 * so it has no save or delete methods.
 */
public interface PublicCourseRepository extends Repository<Course, Long> {

    String SELECT_PUBLIC = "SELECT new com.educore.publicapi.PublicCourseResponse("
            + "c.name, c.slug, c.term, c.instructor, c.description, c.updatedAt) FROM Course c ";

    @Query(value = SELECT_PUBLIC + "WHERE c.published = true",
            countQuery = "SELECT count(c) FROM Course c WHERE c.published = true")
    Page<PublicCourseResponse> findPublished(Pageable pageable);

    @Query(SELECT_PUBLIC + "WHERE c.published = true AND c.slug = :slug")
    Optional<PublicCourseResponse> findPublishedBySlug(@Param("slug") String slug);

    @Query(SELECT_PUBLIC + "WHERE c.published = true")
    List<PublicCourseResponse> findPublishedSlice(Pageable pageable);

    @Query("SELECT count(c) FROM Course c WHERE c.published = true")
    long countPublished();
}
