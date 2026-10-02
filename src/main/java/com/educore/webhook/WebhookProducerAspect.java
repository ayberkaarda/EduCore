package com.educore.webhook;

import com.educore.course.CourseResponse;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Publishes {@code course.updated} and {@code account.deleted} after the producing service method returned.
 * The advice is the outermost one after Spring's {@code ExposeInvocationInterceptor}
 * ({@code HIGHEST_PRECEDENCE + 1}), so it runs after the method's
 * transaction committed: a change that rolls back never produces an event. A failure to queue the event is
 * logged and does not affect the already committed change.
 * <ul>
 *   <li>{@code course.updated}: {@code CourseService.create/update/delete}, data
 *       {@code {courseId, action: CREATED|UPDATED|DELETED}};</li>
 *   <li>{@code account.deleted}: {@code AccountAdminService.softDelete}, data {@code {accountId, mode: SOFT}}.</li>
 * </ul>
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class WebhookProducerAspect {

    private static final Logger log = LoggerFactory.getLogger(WebhookProducerAspect.class);

    private final WebhookPublisher publisher;

    public WebhookProducerAspect(WebhookPublisher publisher) {
        this.publisher = publisher;
    }

    @AfterReturning(pointcut = "execution(* com.educore.course.CourseService.create(..))", returning = "created")
    public void courseCreated(CourseResponse created) {
        publish(WebhookEvent.COURSE_UPDATED, Map.of("courseId", created.id(), "action", "CREATED"));
    }

    @AfterReturning(pointcut = "execution(* com.educore.course.CourseService.update(long, ..)) && args(courseId, ..)",
            argNames = "courseId")
    public void courseUpdated(long courseId) {
        publish(WebhookEvent.COURSE_UPDATED, Map.of("courseId", courseId, "action", "UPDATED"));
    }

    @AfterReturning(pointcut = "execution(* com.educore.course.CourseService.delete(long)) && args(courseId)",
            argNames = "courseId")
    public void courseDeleted(long courseId) {
        publish(WebhookEvent.COURSE_UPDATED, Map.of("courseId", courseId, "action", "DELETED"));
    }

    @AfterReturning(pointcut = "execution(* com.educore.account.AccountAdminService.softDelete(*, long)) "
            + "&& args(*, accountId)", argNames = "accountId")
    public void accountDeleted(long accountId) {
        publish(WebhookEvent.ACCOUNT_DELETED, Map.of("accountId", accountId, "mode", "SOFT"));
    }

    private void publish(WebhookEvent event, Map<String, Object> data) {
        try {
            publisher.publish(event, data);
        } catch (RuntimeException e) {
            log.error("Webhook event could not be queued event={}", event.value(), e);
        }
    }
}
