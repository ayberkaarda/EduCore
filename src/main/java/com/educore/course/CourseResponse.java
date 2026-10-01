package com.educore.course;

import com.educore.entity.Course;

/** A catalog course. */
public record CourseResponse(Long id, String name, String term, String instructor) {

    public static CourseResponse of(Course course) {
        return new CourseResponse(course.getId(), course.getName(), course.getTerm(), course.getInstructor());
    }
}
