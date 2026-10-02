package com.educore.course;

import com.educore.common.web.ApiProblemException;
import com.educore.entity.Course;
import com.educore.repository.CourseRepository;
import com.educore.security.audit.AuditService;
import com.educore.security.audit.SecurityEventType;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The course catalog. Reading is open to every authenticated caller; changes are ADMIN only and each one
 * writes a {@code COURSE_CHANGED} event.
 * <p>
 * Public catalog rules (P9): a course's slug cannot change while it is published (409
 * {@code course/slug-immutable}; unpublish first), a slug used by another course is 409
 * {@code course/slug-taken} whether the conflict is seen by the pre-check or by the unique index, and a
 * generated slug that loses a race against a concurrent insert is generated again in a fresh transaction (at
 * most {@value #SLUG_ATTEMPTS} attempts). Create and update therefore run in their own transaction
 * ({@code REQUIRES_NEW}) per attempt. Every change visible to anonymous visitors advances the
 * {@link CatalogRevision} in the same transaction.
 */
@Service
public class CourseService {

    static final String NOT_FOUND = "course/not-found";
    static final String SLUG_TAKEN = "course/slug-taken";
    static final String SLUG_IMMUTABLE = "course/slug-immutable";
    static final String SLUG_CONSTRAINT = "uk_course_slug";
    static final int SLUG_ATTEMPTS = 3;

    private final CourseRepository courseRepository;
    private final AuditService auditService;
    private final CatalogRevision catalogRevision;
    private final TransactionTemplate attemptTransaction;

    public CourseService(CourseRepository courseRepository, AuditService auditService,
                         CatalogRevision catalogRevision, PlatformTransactionManager transactionManager) {
        this.courseRepository = courseRepository;
        this.auditService = auditService;
        this.catalogRevision = catalogRevision;
        this.attemptTransaction = new TransactionTemplate(transactionManager);
        this.attemptTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(readOnly = true)
    public List<CourseResponse> catalog() {
        return courseRepository.findAll(Sort.by("name")).stream().map(CourseResponse::of).toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    public CourseResponse create(CourseRequest request) {
        return withSlugRetry(request, () -> {
            Course course = new Course();
            apply(course, request);
            Course saved = courseRepository.saveAndFlush(course);
            if (saved.isPublished()) {
                catalogRevision.advance();
            }
            auditService.recordAction(SecurityEventType.COURSE_CHANGED, null,
                    Map.of("action", "CREATED", "courseId", saved.getId()));
            return CourseResponse.of(saved);
        });
    }

    @PreAuthorize("hasRole('ADMIN')")
    public CourseResponse update(long id, CourseRequest request) {
        return withSlugRetry(request, () -> {
            Course course = find(id);
            boolean wasPublished = course.isPublished();
            apply(course, request);
            Course saved = courseRepository.saveAndFlush(course);
            if (wasPublished || saved.isPublished()) {
                catalogRevision.advance();
            }
            auditService.recordAction(SecurityEventType.COURSE_CHANGED, null,
                    Map.of("action", "UPDATED", "courseId", id));
            return CourseResponse.of(saved);
        });
    }

    /** Fails with 409 (unique/foreign key violation) while accounts are still enrolled in the course. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void delete(long id) {
        Course course = find(id);
        boolean wasPublished = course.isPublished();
        courseRepository.delete(course);
        courseRepository.flush();
        if (wasPublished) {
            catalogRevision.advance();
        }
        auditService.recordAction(SecurityEventType.COURSE_CHANGED, null,
                Map.of("action", "DELETED", "courseId", id));
    }

    private Course find(long id) {
        return courseRepository.findById(id)
                .orElseThrow(() -> ApiProblemException.notFound(NOT_FOUND, "Course not found."));
    }

    /**
     * Runs {@code work} in a new transaction. When the slug unique index rejects it, an explicit slug is
     * answered with 409 {@code course/slug-taken} and a generated one is generated again in a fresh
     * transaction (the losing transaction is rolled back, its audit event with it).
     */
    private CourseResponse withSlugRetry(CourseRequest request, Supplier<CourseResponse> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return attemptTransaction.execute(status -> work.get());
            } catch (DataIntegrityViolationException e) {
                if (!violates(e, SLUG_CONSTRAINT)) {
                    throw e;
                }
                if (request.slug() != null || attempt >= SLUG_ATTEMPTS) {
                    throw slugTaken();
                }
            }
        }
    }

    /** Whether {@code failure} was caused by a violation of the named database constraint. */
    static boolean violates(Throwable failure, String constraint) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraint.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getMessage() != null
                    && sql.getMessage().contains("\"" + constraint + "\"")) {
                return true;
            }
        }
        return false;
    }

    private static ApiProblemException slugTaken() {
        return ApiProblemException.conflict(SLUG_TAKEN, "The course slug is already in use.");
    }

    /**
     * Copies the request onto the course. Public catalog fields that are {@code null} keep their stored
     * values; a course without a slug gets the first free one derived from its name.
     *
     * @throws ApiProblemException 409 {@code course/slug-immutable} when the slug of a published course would
     *                             change, 409 {@code course/slug-taken} when the requested slug belongs to
     *                             another course
     */
    private void apply(Course course, CourseRequest request) {
        boolean wasPublished = course.isPublished();
        course.setName(request.name().trim());
        course.setTerm(request.term());
        course.setInstructor(request.instructor());
        if (request.slug() != null && !request.slug().equals(course.getSlug())) {
            if (wasPublished) {
                throw ApiProblemException.conflict(SLUG_IMMUTABLE,
                        "The slug of a published course cannot change. Unpublish the course first.");
            }
            boolean taken = course.getId() == null
                    ? courseRepository.existsBySlug(request.slug())
                    : courseRepository.existsBySlugAndIdNot(request.slug(), course.getId());
            if (taken) {
                throw slugTaken();
            }
            course.setSlug(request.slug());
        } else if (course.getSlug() == null) {
            course.setSlug(CourseSlugs.unique(course.getName(), courseRepository::existsBySlug));
        }
        if (request.description() != null) {
            course.setDescription(request.description().isBlank() ? null : request.description().trim());
        }
        if (request.published() != null) {
            course.setPublished(request.published());
        }
    }
}
