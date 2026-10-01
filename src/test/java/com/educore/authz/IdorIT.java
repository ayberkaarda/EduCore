package com.educore.authz;

import com.educore.enrollment.EnrollmentService;
import com.educore.entity.Account;
import com.educore.entity.Course;
import com.educore.entity.Role;
import com.educore.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The three pre-P3 IDOR paths (list, enroll and drop another account's courses) are closed: over HTTP and
 * also at the service layer ({@code #accountId == principal.id or hasRole('ADMIN')}).
 */
class IdorIT extends AuthzIntegrationSupport {

    @Autowired
    private EnrollmentService enrollmentService;

    @Test
    void userCannotReadAnotherAccountsCourses() throws Exception {
        Account user = account(Role.USER);
        Account victim = account(Role.USER);
        Course course = course();
        enroll(victim, course);

        MvcResult result = perform(user, get("/api/v1/admin/accounts/" + victim.getId() + "/enrollments"), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(course.getName());
    }

    @Test
    void userCannotEnrollAnotherAccount() throws Exception {
        Account user = account(Role.USER);
        Account victim = account(Role.USER);
        Course course = course();

        MvcResult result = perform(user, post("/api/v1/admin/accounts/" + victim.getId() + "/enrollments"),
                map("courseId", course.getId()));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(enrollmentRepository.existsByAccountIdAndCourseId(victim.getId(), course.getId())).isFalse();
    }

    @Test
    void userCannotDropAnotherAccountsCourse() throws Exception {
        Account user = account(Role.USER);
        Account victim = account(Role.USER);
        Course course = course();
        enroll(victim, course);
        enroll(user, course);

        MvcResult viaAdminRoute = perform(user,
                delete("/api/v1/admin/accounts/" + victim.getId() + "/enrollments/" + course.getId()), null);
        MvcResult viaOwnRoute = perform(user, delete("/api/v1/me/enrollments/" + course.getId()), null);

        assertThat(viaAdminRoute.getResponse().getStatus()).isEqualTo(403);
        // The /me route only ever drops the caller's own enrollment.
        assertThat(viaOwnRoute.getResponse().getStatus()).isEqualTo(204);
        assertThat(enrollmentRepository.existsByAccountIdAndCourseId(victim.getId(), course.getId())).isTrue();
        assertThat(enrollmentRepository.existsByAccountIdAndCourseId(user.getId(), course.getId())).isFalse();
    }

    @Test
    void ownEnrollmentsListOnlyTheCallersCourses() throws Exception {
        Account user = account(Role.USER);
        Account other = account(Role.USER);
        Course mine = course();
        Course theirs = course();
        enroll(user, mine);
        enroll(other, theirs);

        String listing = perform(user, get("/api/v1/me/enrollments"), null).getResponse().getContentAsString();

        assertThat(listing).contains(mine.getName()).doesNotContain(theirs.getName());
    }

    @Test
    void serviceOwnershipCheckRejectsAnotherAccountIdForAUser() {
        Account user = account(Role.USER);
        Account victim = account(Role.USER);
        Course course = course();
        enroll(victim, course);
        authenticateAs(AuthenticatedUser.of(user));

        assertThatThrownBy(() -> enrollmentService.coursesOf(victim.getId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> enrollmentService.enroll(victim.getId(), course().getId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> enrollmentService.drop(victim.getId(), course.getId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(enrollmentRepository.existsByAccountIdAndCourseId(victim.getId(), course.getId())).isTrue();

        // The same expression lets the owner and an ADMIN through.
        assertThat(enrollmentService.coursesOf(user.getId())).isEmpty();
        authenticateAs(AuthenticatedUser.of(account(Role.ADMIN)));
        assertThat(enrollmentService.coursesOf(victim.getId())).hasSize(1);
    }

    @Test
    void adminCanManageAnyAccountsEnrollments() throws Exception {
        Account admin = account(Role.ADMIN);
        Account student = account(Role.USER);
        Course course = course();

        assertThat(perform(admin, post("/api/v1/admin/accounts/" + student.getId() + "/enrollments"),
                map("courseId", course.getId())).getResponse().getStatus()).isEqualTo(201);
        assertThat(perform(admin, post("/api/v1/admin/accounts/" + student.getId() + "/enrollments"),
                map("courseId", course.getId())).getResponse().getStatus()).isEqualTo(409);
        assertThat(perform(admin, get("/api/v1/admin/accounts/" + student.getId() + "/enrollments"), null)
                .getResponse().getContentAsString()).contains(course.getName());
        assertThat(perform(admin, delete("/api/v1/admin/accounts/" + student.getId() + "/enrollments/"
                + course.getId()), null).getResponse().getStatus()).isEqualTo(204);
        assertThat(enrollmentRepository.existsByAccountIdAndCourseId(student.getId(), course.getId())).isFalse();
        assertThat(perform(admin, get("/api/v1/admin/accounts/" + Long.MAX_VALUE + "/enrollments"), null)
                .getResponse().getStatus()).isEqualTo(404);
    }
}
