package com.educore.common.web;

import org.springframework.data.domain.Page;

import java.util.List;

/** A stable JSON shape for one page of results (Spring's {@code PageImpl} is not a serialization contract). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }
}
