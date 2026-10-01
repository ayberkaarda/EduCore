package com.educore.security.audit;

import com.educore.common.web.PageResponse;
import com.educore.common.web.Paging;
import com.educore.security.AuthenticatedUser;
import com.educore.security.ClientIpResolver;
import com.educore.security.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.util.Map;

/**
 * The single writer of the {@code security_event} audit trail. Every row carries the request id of the
 * current request (MDC, {@link RequestIdFilter}).
 * <p>
 * {@code details} must hold ids, enum values and field names only: never passwords, tokens, raw usernames,
 * personal names, student numbers or student IP addresses. Events are written in the caller's transaction,
 * so a mutation that rolls back leaves no event behind.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final SecurityEventRepository repository;
    private final ClientIpResolver clientIpResolver;
    private final Clock clock;

    public AuditService(SecurityEventRepository repository, ClientIpResolver clientIpResolver, Clock clock) {
        this.repository = repository;
        this.clientIpResolver = clientIpResolver;
        this.clock = clock;
    }

    /** Records an event whose actor and client IP the caller already knows (authentication flows). */
    public void record(SecurityEventType type, Long actorAccountId, Long targetAccountId, String ip,
                       Map<String, Object> details) {
        String requestId = RequestIdFilter.currentRequestId();
        repository.save(new SecurityEvent(type, actorAccountId, targetAccountId, ip, requestId, clock.instant(),
                details == null || details.isEmpty() ? null : Map.copyOf(details)));
        log.info("SECURITY_EVENT type={} actor={} target={} requestId={}", type, actorAccountId, targetAccountId,
                requestId);
    }

    /**
     * Records a mutation performed by the authenticated caller of the current request: the actor is the
     * {@link AuthenticatedUser} principal and the IP is resolved from the current HTTP request.
     *
     * @param targetAccountId the affected account, or {@code null} when the target is not an account
     */
    public void recordAction(SecurityEventType type, Long targetAccountId, Map<String, Object> details) {
        record(type, currentActorId(), targetAccountId, currentClientIp(), details);
    }

    /** The audit trail, newest first (ADMIN only). */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public PageResponse<SecurityEventResponse> page(int page, int size) {
        Sort newestFirst = Sort.by(Sort.Direction.DESC, "at").and(Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.of(repository.findAll(Paging.of(page, size, newestFirst)).map(SecurityEventResponse::of));
    }

    private static Long currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user.id();
        }
        return null;
    }

    private String currentClientIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            HttpServletRequest request = servlet.getRequest();
            return clientIpResolver.resolve(request);
        }
        return null;
    }
}
