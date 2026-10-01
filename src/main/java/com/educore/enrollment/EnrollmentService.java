package com.educore.enrollment;

import com.educore.common.web.ApiProblemException;
import com.educore.course.CourseResponse;
import com.educore.entity.Account;
import com.educore.entity.Course;
import com.educore.entity.Enrollment;
import com.educore.repository.AccountRepository;
import com.educore.repository.CourseRepository;
import com.educore.repository.EnrollmentRepository;
import com.educore.security.ActiveAccount;
import com.educore.security.AuthenticatedUser;
import com.educore.security.audit.AuditService;
import com.educore.security.audit.SecurityEventType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Enrollments of one account.
 * <ul>
 *   <li>Self-service methods ({@link #coursesOf}, {@link #enroll}, {@link #drop}) are guarded by
 *       {@code #accountId == principal.id or hasRole('ADMIN')}: a USER can only touch their own enrollments,
 *       even if a caller passed another id. They write an event only when the actor is not the owner.</li>
 *   <li>Admin methods ({@link #enrollAsAdmin}, {@link #dropAsAdmin}) back the {@code /api/v1/admin/...}
 *       routes, require {@code hasRole('ADMIN')} and always write an {@code ENROLLMENT_CHANGED} event when
 *       something changed, also when the ADMIN targets their own account.</li>
 * </ul>
 */
@Service
public class EnrollmentService {

    private final EnrollmentRepository enrollmentRepository;
    private final AccountRepository accountRepository;
    private final CourseRepository courseRepository;
    private final AuditService auditService;

    public EnrollmentService(EnrollmentRepository enrollmentRepository, AccountRepository accountRepository,
                             CourseRepository courseRepository, AuditService auditService) {
        this.enrollmentRepository = enrollmentRepository;
        this.accountRepository = accountRepository;
        this.courseRepository = courseRepository;
        this.auditService = auditService;
    }

    @PreAuthorize("#accountId == principal.id or hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<CourseResponse> coursesOf(Long accountId) {
        requireActiveAccount(accountId);
        return enrollmentRepository.findCoursesByAccountId(accountId).stream().map(CourseResponse::of).toList();
    }

    @PreAuthorize("#accountId == principal.id or hasRole('ADMIN')")
    @Transactional
    public CourseResponse enroll(Long accountId, Long courseId) {
        CourseResponse course = doEnroll(accountId, courseId);
        if (!isOwner(accountId)) {
            audit(accountId, "ENROLLED", courseId);
        }
        return course;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CourseResponse enrollAsAdmin(Long accountId, Long courseId) {
        CourseResponse course = doEnroll(accountId, courseId);
        audit(accountId, "ENROLLED", courseId);
        return course;
    }

    private CourseResponse doEnroll(Long accountId, Long courseId) {
        Account account = requireActiveAccount(accountId);
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> ApiProblemException.notFound("course/not-found", "Course not found."));
        if (enrollmentRepository.existsByAccountIdAndCourseId(accountId, courseId)) {
            throw ApiProblemException.conflict("enrollment/already-enrolled",
                    "The account is already enrolled in this course.");
        }
        enrollmentRepository.saveAndFlush(Enrollment.builder().account(account).course(course).build());
        return CourseResponse.of(course);
    }

    /** Idempotent: dropping a course the account is not enrolled in changes nothing. */
    @PreAuthorize("#accountId == principal.id or hasRole('ADMIN')")
    @Transactional
    public void drop(Long accountId, Long courseId) {
        if (doDrop(accountId, courseId) && !isOwner(accountId)) {
            audit(accountId, "DROPPED", courseId);
        }
    }

    /** Idempotent like {@link #drop}; audited whenever an enrollment was removed. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void dropAsAdmin(Long accountId, Long courseId) {
        if (doDrop(accountId, courseId)) {
            audit(accountId, "DROPPED", courseId);
        }
    }

    private boolean doDrop(Long accountId, Long courseId) {
        requireActiveAccount(accountId);
        return enrollmentRepository.deleteByAccountIdAndCourseId(accountId, courseId) > 0;
    }

    private Account requireActiveAccount(Long accountId) {
        Account account = accountRepository.findById(accountId).orElse(null);
        if (!ActiveAccount.isActive(account)) {
            throw ApiProblemException.notFound("account/not-found", "Account not found.");
        }
        return account;
    }

    private static boolean isOwner(Long accountId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                && user.id() == accountId;
    }

    private void audit(Long accountId, String action, Long courseId) {
        auditService.recordAction(SecurityEventType.ENROLLMENT_CHANGED, accountId,
                Map.of("action", action, "courseId", courseId));
    }
}
