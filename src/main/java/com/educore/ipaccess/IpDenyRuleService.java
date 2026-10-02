package com.educore.ipaccess;

import com.educore.common.web.ApiProblemException;
import com.educore.common.web.PageResponse;
import com.educore.common.web.Paging;
import com.educore.security.AuthenticatedUser;
import com.educore.security.TrustedProxies;
import com.educore.security.audit.AuditService;
import com.educore.security.audit.SecurityEventType;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/**
 * Request-level deny rules (ADMIN only). Every change writes an {@code IP_RULE_CHANGED} event and invalidates
 * {@link IpDenyRuleCache}. A rule may never cover the ADMIN's own current client IP (409
 * {@code ip-rule/self-deny}) or a trusted proxy (409 {@code ip-rule/trusted-proxy}): either would lock the
 * operator, or every client behind the proxy, out.
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class IpDenyRuleService {

    static final String INVALID = "ip-rule/invalid";
    static final String IPV6_UNSUPPORTED = "ip-rule/ipv6-unsupported";
    static final String NOT_FOUND = "ip-rule/not-found";
    static final String SELF_DENY = "ip-rule/self-deny";
    static final String TRUSTED_PROXY = "ip-rule/trusted-proxy";

    private final IpDenyRuleRepository repository;
    private final IpDenyRuleCache cache;
    private final TrustedProxies trustedProxies;
    private final AuditService auditService;
    private final Clock clock;

    public IpDenyRuleService(IpDenyRuleRepository repository, IpDenyRuleCache cache, TrustedProxies trustedProxies,
                             AuditService auditService, Clock clock) {
        this.repository = repository;
        this.cache = cache;
        this.trustedProxies = trustedProxies;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** Newest first. */
    @Transactional(readOnly = true)
    public PageResponse<IpDenyRuleResponse> page(int page, int size) {
        return PageResponse.of(repository.findAll(Paging.of(page, size, Sort.by(Sort.Direction.DESC, "id")))
                .map(IpDenyRuleResponse::of));
    }

    @Transactional(readOnly = true)
    public IpDenyRuleResponse get(long id) {
        return IpDenyRuleResponse.of(find(id));
    }

    /** @param callerIp the resolved client IP of the ADMIN's request */
    @Transactional
    public IpDenyRuleResponse create(IpDenyRuleRequest request, String callerIp) {
        Ipv4Range range = checkedRange(request, callerIp);
        IpDenyRule rule = new IpDenyRule();
        rule.applyRange(request.kind(), range);
        rule.setReason(blankToNull(request.reason()));
        rule.setExpiresAt(request.expiresAt());
        rule.setSource(IpDenyRuleSource.MANUAL);
        rule.setCreatedBy(currentAccountId());
        rule.setCreatedAt(clock.instant());
        IpDenyRule saved = repository.save(rule);
        audit("CREATED", saved);
        return IpDenyRuleResponse.of(saved);
    }

    /** Replaces kind, value, reason and expiry; {@code source}, creator and creation time are kept. */
    @Transactional
    public IpDenyRuleResponse update(long id, IpDenyRuleRequest request, String callerIp) {
        IpDenyRule rule = find(id);
        Ipv4Range range = checkedRange(request, callerIp);
        rule.applyRange(request.kind(), range);
        rule.setReason(blankToNull(request.reason()));
        rule.setExpiresAt(request.expiresAt());
        IpDenyRule saved = repository.save(rule);
        audit("UPDATED", saved);
        return IpDenyRuleResponse.of(saved);
    }

    @Transactional
    public void delete(long id) {
        IpDenyRule rule = find(id);
        repository.delete(rule);
        audit("DELETED", rule);
    }

    private IpDenyRule find(long id) {
        return repository.findById(id)
                .orElseThrow(() -> ApiProblemException.notFound(NOT_FOUND, "IP deny rule not found."));
    }

    private Ipv4Range checkedRange(IpDenyRuleRequest request, String callerIp) {
        if (IpRanges.isIpv6(request.value())) {
            throw ApiProblemException.badRequest(IPV6_UNSUPPORTED,
                    "IPv6 is not supported; use an IPv4 address, range or CIDR block.");
        }
        Ipv4Range range;
        try {
            range = IpRanges.parse(request.kind(), request.value());
        } catch (IllegalArgumentException e) {
            throw ApiProblemException.badRequest(INVALID, "The value is not a valid IPv4 " + switch (request.kind()) {
                case STATIC -> "address.";
                case RANGE -> "range (start-end, end not before start).";
                case CIDR -> "CIDR block (network address and prefix 0-32).";
            });
        }
        Optional<Ipv4> caller = ClientAddress.parse(callerIp).ipv4();
        if (caller.isPresent() && range.contains(caller.get())) {
            throw new ApiProblemException(HttpStatus.CONFLICT, SELF_DENY,
                    "The rule would deny your own current IP address.");
        }
        if (trustedProxies.overlaps(range)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, TRUSTED_PROXY,
                    "The rule would deny a trusted reverse proxy.");
        }
        return range;
    }

    private void audit(String action, IpDenyRule rule) {
        auditService.recordAction(SecurityEventType.IP_RULE_CHANGED, null, Map.of("action", action,
                "ipRuleId", rule.getId(), "kind", rule.getKind().name(), "source", rule.getSource().name()));
        cache.invalidateAfterCommit();
    }

    private static Long currentAccountId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                ? user.id() : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
