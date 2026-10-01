package com.educore.enrollment;

import jakarta.validation.constraints.NotNull;

/**
 * Body of the enroll endpoints. The account always comes from the path ({@code /admin/accounts/{accountId}})
 * or from the caller ({@code /me}); an {@code accountId} in the body is ignored.
 */
public record EnrollRequest(@NotNull Long courseId) {
}
