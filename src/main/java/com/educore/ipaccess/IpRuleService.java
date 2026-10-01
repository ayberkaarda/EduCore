package com.educore.ipaccess;

import com.educore.common.web.ApiProblemException;
import com.educore.entity.IpBlock;
import com.educore.repository.IpBlockRepository;
import com.educore.security.audit.AuditService;
import com.educore.security.audit.SecurityEventType;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** IP allocation rules (ADMIN only). Every change writes an {@code IP_RULE_CHANGED} event. */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class IpRuleService {

    private final IpBlockRepository ipBlockRepository;
    private final AuditService auditService;

    public IpRuleService(IpBlockRepository ipBlockRepository, AuditService auditService) {
        this.ipBlockRepository = ipBlockRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<IpRuleResponse> list() {
        return ipBlockRepository.findAll(Sort.by("id")).stream().map(IpRuleResponse::of).toList();
    }

    @Transactional
    public IpRuleResponse create(IpRuleRequest request) {
        IpRuleRanges.Range range = IpRuleRanges.parse(request.type(), request.originalValue());
        IpBlock saved = ipBlockRepository.save(IpBlock.builder()
                .type(request.type().name())
                .originalValue(request.originalValue().trim())
                .startIp(range.start())
                .endIp(range.end())
                .build());
        auditService.recordAction(SecurityEventType.IP_RULE_CHANGED, null,
                Map.of("action", "CREATED", "ipRuleId", saved.getId(), "type", saved.getType()));
        return IpRuleResponse.of(saved);
    }

    @Transactional
    public void delete(long id) {
        IpBlock block = ipBlockRepository.findById(id)
                .orElseThrow(() -> ApiProblemException.notFound("ip-rule/not-found", "IP rule not found."));
        ipBlockRepository.delete(block);
        auditService.recordAction(SecurityEventType.IP_RULE_CHANGED, null,
                Map.of("action", "DELETED", "ipRuleId", id, "type", String.valueOf(block.getType())));
    }
}
