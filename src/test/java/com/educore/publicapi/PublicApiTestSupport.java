package com.educore.publicapi;

import com.educore.authz.AuthzIntegrationSupport;
import com.educore.entity.Course;

import java.util.UUID;

/**
 * Fixtures for the public API tests: published and unpublished courses with unique names and slugs, removed
 * after every test (by name, through {@link AuthzIntegrationSupport}), so the shared database keeps only its
 * seed data and no other test class sees a published course.
 */
abstract class PublicApiTestSupport extends AuthzIntegrationSupport {

    /** A unique, valid slug with the given prefix. */
    static String uniqueSlug(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    protected Course publishedCourse(String slug, String description) {
        return saveCourse(slug, description, true);
    }

    protected Course unpublishedCourse(String slug, String description) {
        return saveCourse(slug, description, false);
    }

    private Course saveCourse(String slug, String description, boolean published) {
        String name = "Public Course " + slug;
        cleanUpCourseName(name);
        return courseRepository.saveAndFlush(Course.builder()
                .name(name)
                .term("2026/1")
                .instructor("Instructor " + slug.substring(0, 6))
                .slug(slug)
                .description(description)
                .published(published)
                .build());
    }
}
