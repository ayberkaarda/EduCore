package com.educore.enrollment;

import com.educore.course.CourseResponse;
import com.educore.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Enrollments.
 * <ul>
 *   <li>{@code /api/v1/me/enrollments}: the caller's own enrollments (any authenticated caller). The account
 *       is always the caller; nothing in the request can address another account.</li>
 *   <li>{@code /api/v1/admin/accounts/{accountId}/enrollments}: any account's enrollments (ADMIN).</li>
 * </ul>
 * The /me routes use the self-service {@link EnrollmentService} methods ({@code #accountId == principal.id or
 * hasRole('ADMIN')}); the admin routes use the {@code ...AsAdmin} methods, which always audit.
 */
@RestController
@Validated
public class EnrollmentController {

    private final EnrollmentService enrollmentService;

    public EnrollmentController(EnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    @GetMapping("/api/v1/me/enrollments")
    public List<CourseResponse> myCourses(@AuthenticationPrincipal AuthenticatedUser user) {
        return enrollmentService.coursesOf(user.id());
    }

    @PostMapping("/api/v1/me/enrollments")
    public ResponseEntity<CourseResponse> enrollMe(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @Valid @RequestBody EnrollRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(enrollmentService.enroll(user.id(), request.courseId()));
    }

    @DeleteMapping("/api/v1/me/enrollments/{courseId}")
    public ResponseEntity<Void> dropMine(@AuthenticationPrincipal AuthenticatedUser user,
                                         @PathVariable @Positive long courseId) {
        enrollmentService.drop(user.id(), courseId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/v1/admin/accounts/{accountId}/enrollments")
    public List<CourseResponse> coursesOf(@PathVariable @Positive long accountId) {
        return enrollmentService.coursesOf(accountId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/v1/admin/accounts/{accountId}/enrollments")
    public ResponseEntity<CourseResponse> enroll(@PathVariable @Positive long accountId,
                                                 @Valid @RequestBody EnrollRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(enrollmentService.enrollAsAdmin(accountId, request.courseId()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/api/v1/admin/accounts/{accountId}/enrollments/{courseId}")
    public ResponseEntity<Void> drop(@PathVariable @Positive long accountId, @PathVariable @Positive long courseId) {
        enrollmentService.dropAsAdmin(accountId, courseId);
        return ResponseEntity.noContent().build();
    }
}
