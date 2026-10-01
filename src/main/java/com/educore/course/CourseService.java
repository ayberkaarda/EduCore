package com.educore.course;

import com.educore.common.web.ApiProblemException;
import com.educore.entity.Course;
import com.educore.repository.CourseRepository;
import com.educore.security.audit.AuditService;
import com.educore.security.audit.SecurityEventType;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * The course catalog. Reading is open to every authenticated caller; changes are ADMIN only and each one
 * writes a {@code COURSE_CHANGED} event.
 */
@Service
public class CourseService {

    static final String NOT_FOUND = "course/not-found";

    private final CourseRepository courseRepository;
    private final AuditService auditService;

    public CourseService(CourseRepository courseRepository, AuditService auditService) {
        this.courseRepository = courseRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<CourseResponse> catalog() {
        return courseRepository.findAll(Sort.by("name")).stream().map(CourseResponse::of).toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CourseResponse create(CourseRequest request) {
        Course course = new Course();
        apply(course, request);
        Course saved = courseRepository.saveAndFlush(course);
        auditService.recordAction(SecurityEventType.COURSE_CHANGED, null,
                Map.of("action", "CREATED", "courseId", saved.getId()));
        return CourseResponse.of(saved);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CourseResponse update(long id, CourseRequest request) {
        Course course = find(id);
        apply(course, request);
        Course saved = courseRepository.saveAndFlush(course);
        auditService.recordAction(SecurityEventType.COURSE_CHANGED, null,
                Map.of("action", "UPDATED", "courseId", id));
        return CourseResponse.of(saved);
    }

    /** Fails with 409 (unique/foreign key violation) while accounts are still enrolled in the course. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void delete(long id) {
        courseRepository.delete(find(id));
        courseRepository.flush();
        auditService.recordAction(SecurityEventType.COURSE_CHANGED, null,
                Map.of("action", "DELETED", "courseId", id));
    }

    private Course find(long id) {
        return courseRepository.findById(id)
                .orElseThrow(() -> ApiProblemException.notFound(NOT_FOUND, "Course not found."));
    }

    private static void apply(Course course, CourseRequest request) {
        course.setName(request.name().trim());
        course.setTerm(request.term());
        course.setInstructor(request.instructor());
    }
}
