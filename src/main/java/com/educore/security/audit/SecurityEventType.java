package com.educore.security.audit;

/** Types written to {@code security_event.type}. */
public enum SecurityEventType {
    AUTH_LOGIN_SUCCESS,
    AUTH_LOGIN_FAILURE,
    AUTH_LOCKED,
    AUTH_REFRESH_REUSE,
    PASSWORD_CHANGED,
    ACCOUNT_CREATED,
    ACCOUNT_UPDATED,
    ACCOUNT_DELETED,
    /** The owner requested the deletion of their account ({@code DELETE /api/v1/me}); grace period started. */
    ACCOUNT_DELETION_REQUESTED,
    /** A deactivated or pending-deletion account became active again (owner or ADMIN). */
    ACCOUNT_RESTORED,
    /** An account was hard-deleted (ADMIN {@code mode=hard} or the purge job); the target is a pseudonym. */
    ACCOUNT_PURGED,
    /** A user downloaded their data export ({@code GET /api/v1/me/export}). */
    DATA_EXPORTED,
    /** An ADMIN cleared the failed login attempts of an account ({@code POST .../unlock-login}). */
    ACCOUNT_LOGIN_UNLOCKED,
    ROLE_CHANGED,
    ENROLLMENT_CHANGED,
    COURSE_CHANGED,
    /** A request-level deny rule was created, updated or deleted (by an ADMIN or automatically). */
    IP_RULE_CHANGED,
    /** A range of the student IP allow-list was created or deleted. */
    IP_ALLOCATION_CHANGED,
    JOB_LOGS_DELETED,
    IMPORT_UPLOADED,
    WEBHOOK_CHANGED,
    WEBHOOK_TEST_REQUESTED
}
