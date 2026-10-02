package com.educore.webhook;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Events delivered to webhook subscriptions ({@code X-EduCore-Event}). {@link #TEST} is sent only by the
 * "send test event" action and cannot be subscribed to.
 */
public enum WebhookEvent {

    IMPORT_COMPLETED("import.completed"),
    IMPORT_FAILED("import.failed"),
    COURSE_UPDATED("course.updated"),
    ACCOUNT_DELETED("account.deleted"),
    TEST("webhook.test");

    /** Pattern for one subscribable event name in a request body. */
    public static final String SUBSCRIBABLE_PATTERN = "^(import\\.completed|import\\.failed|course\\.updated|account\\.deleted)$";

    private final String value;

    WebhookEvent(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean subscribable() {
        return this != TEST;
    }

    public static List<WebhookEvent> subscribableEvents() {
        return Arrays.stream(values()).filter(WebhookEvent::subscribable).toList();
    }

    public static Optional<WebhookEvent> fromValue(String value) {
        return Arrays.stream(values()).filter(event -> event.value.equals(value)).findFirst();
    }
}
