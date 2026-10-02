package com.educore.ipaccess;

/** An IP allocation range as returned by the admin API. */
public record IpAllocationResponse(Long id, String type, String originalValue) {

    static IpAllocationResponse of(IpAllocationRange range) {
        return new IpAllocationResponse(range.getId(), range.getType(), range.getOriginalValue());
    }
}
