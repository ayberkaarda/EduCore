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
    ROLE_CHANGED,
    ENROLLMENT_CHANGED,
    COURSE_CHANGED,
    IP_RULE_CHANGED,
    JOB_LOGS_DELETED
}
