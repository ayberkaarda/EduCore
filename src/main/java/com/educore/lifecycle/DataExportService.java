package com.educore.lifecycle;

import com.educore.common.web.ApiProblemException;
import com.educore.entity.Account;
import com.educore.ratelimit.NamedRateLimits;
import com.educore.repository.AccountRepository;
import com.educore.repository.EnrollmentRepository;
import com.educore.security.ActiveAccount;
import com.educore.security.AuthenticatedUser;
import com.educore.security.audit.AuditService;
import com.educore.security.audit.SecurityEvent;
import com.educore.security.audit.SecurityEventRepository;
import com.educore.security.audit.SecurityEventType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code GET /api/v1/me/export}: the caller's profile, enrollments and own security events as one JSON
 * document ({@link AccountExport}). Limited to {@code educore.lifecycle.export-per-minute} (1) per account
 * through the {@link NamedRateLimits#DATA_EXPORT} bucket (429 {@code rate-limit/exceeded} with
 * {@code Retry-After}); each export writes a {@code DATA_EXPORTED} event (counts only).
 */
@Service
public class DataExportService {

    static final String NOT_FOUND = "account/not-found";

    private final AccountRepository accounts;
    private final EnrollmentRepository enrollments;
    private final SecurityEventRepository events;
    private final NamedRateLimits rateLimits;
    private final AuditService audit;
    private final Clock clock;
    private final int perMinute;
    private final int maxEvents;

    public DataExportService(AccountRepository accounts, EnrollmentRepository enrollments,
                             SecurityEventRepository events, NamedRateLimits rateLimits, AuditService audit,
                             Clock clock, LifecycleProperties properties) {
        this.accounts = accounts;
        this.enrollments = enrollments;
        this.events = events;
        this.rateLimits = rateLimits;
        this.audit = audit;
        this.clock = clock;
        this.perMinute = properties.exportPerMinute();
        this.maxEvents = properties.exportMaxSecurityEvents();
    }

    @Transactional
    public AccountExport export(AuthenticatedUser user) {
        rateLimits.consume(NamedRateLimits.DATA_EXPORT, user.id(), perMinute);
        Account account = accounts.findById(user.id()).filter(ActiveAccount::isActive)
                .orElseThrow(() -> ApiProblemException.notFound(NOT_FOUND, "Account not found."));
        AccountExport.Profile profile = new AccountExport.Profile(account.getId(), account.getUsername(),
                account.getFirstName(), account.getLastName(), account.getStudentNumber(), account.getRole(),
                account.getIpAddress(), account.getStatus());
        List<AccountExport.Enrollment> courses = enrollments.findWithCourseByAccountId(account.getId()).stream()
                .map(enrollment -> new AccountExport.Enrollment(enrollment.getCourse().getId(),
                        enrollment.getCourse().getName(), enrollment.getCourse().getTerm(),
                        enrollment.getCourse().getInstructor(), enrollment.getEnrollmentDate()))
                .toList();
        Sort newestFirst = Sort.by(Sort.Direction.DESC, "at").and(Sort.by(Sort.Direction.DESC, "id"));
        List<SecurityEvent> found = events.findInvolving(account.getId(), PageRequest.of(0, maxEvents + 1, newestFirst));
        boolean truncated = found.size() > maxEvents;
        List<AccountExport.Event> ownEvents = found.stream().limit(maxEvents)
                .map(event -> toExport(event, account.getId())).toList();

        audit.recordAction(SecurityEventType.DATA_EXPORTED, account.getId(),
                Map.of("enrollments", courses.size(), "securityEvents", ownEvents.size()));
        return new AccountExport(AccountExport.FORMAT, clock.instant(), profile, courses, ownEvents, truncated);
    }

    private static AccountExport.Event toExport(SecurityEvent event, long accountId) {
        boolean actor = Objects.equals(event.getActorAccountId(), accountId);
        boolean target = Objects.equals(event.getTargetAccountId(), accountId);
        String involvement = actor && target ? "ACTOR_AND_TARGET" : actor ? "ACTOR" : "TARGET";
        return new AccountExport.Event(event.getType(), event.getAt(), involvement, actor ? event.getIp() : null);
    }
}
