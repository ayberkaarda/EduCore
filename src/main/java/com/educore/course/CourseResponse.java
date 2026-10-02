package com.educore.course;

import com.educore.entity.Course;

/** A catalog course, including its public catalog fields ({@code slug}, {@code description}, {@code published}). */
public record CourseResponse(Long id, String name, String term, String instructor, String slug, String description,
                             boolean published) {

    public static CourseResponse of(Course course) {
        return new CourseResponse(course.getId(), course.getName(), course.getTerm(), course.getInstructor(),
                course.getSlug(), course.getDescription(), course.isPublished());
    }
}
