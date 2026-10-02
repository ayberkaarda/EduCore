package com.educore.ipaccess;

import com.educore.common.web.ApiProblemException;
import com.educore.security.audit.AuditService;
import com.educore.security.audit.SecurityEventType;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** The student IP allow-list (ADMIN only). Every change writes an {@code IP_ALLOCATION_CHANGED} event. */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class IpAllocationService {

    static final String INVALID = "ip-allocation/invalid";
    static final String NOT_FOUND = "ip-allocation/not-found";

    private final IpAllocationRangeRepository repository;
    private final AuditService auditService;

    public IpAllocationService(IpAllocationRangeRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<IpAllocationResponse> list() {
        return repository.findAll(Sort.by("id")).stream().map(IpAllocationResponse::of).toList();
    }

    @Transactional
    public IpAllocationResponse create(IpAllocationRequest request) {
        Ipv4Range range;
        try {
            range = IpRanges.parse(request.type(), request.originalValue());
        } catch (IllegalArgumentException e) {
            throw ApiProblemException.badRequest(INVALID, titleFor(request.type()));
        }
        IpAllocationRange saved = repository.save(IpAllocationRange.builder()
                .type(request.type().name())
                .originalValue(request.originalValue().trim())
                .startIp(range.start().toLong())
                .endIp(range.end().toLong())
                .build());
        auditService.recordAction(SecurityEventType.IP_ALLOCATION_CHANGED, null,
                Map.of("action", "CREATED", "ipAllocationId", saved.getId(), "type", saved.getType()));
        return IpAllocationResponse.of(saved);
    }

    @Transactional
    public void delete(long id) {
        IpAllocationRange range = repository.findById(id)
                .orElseThrow(() -> ApiProblemException.notFound(NOT_FOUND, "IP allocation range not found."));
        repository.delete(range);
        auditService.recordAction(SecurityEventType.IP_ALLOCATION_CHANGED, null,
                Map.of("action", "DELETED", "ipAllocationId", id, "type", String.valueOf(range.getType())));
    }

    private static String titleFor(IpRangeKind type) {
        return switch (type) {
            case STATIC -> "Invalid IPv4 address format.";
            case RANGE -> "Invalid range: use start-end with the end not before the start.";
            case CIDR -> "Invalid CIDR block: use the network address, e.g. 192.168.1.0/24.";
        };
    }
}
