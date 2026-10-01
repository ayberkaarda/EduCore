package com.educore.ipaccess;

import com.educore.entity.IpBlock;

/** An IP allocation rule as returned by the admin API. */
public record IpRuleResponse(Long id, String type, String originalValue) {

    static IpRuleResponse of(IpBlock block) {
        return new IpRuleResponse(block.getId(), block.getType(), block.getOriginalValue());
    }
}
