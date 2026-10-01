package com.educore.common.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Builds {@link Pageable}s from request parameters with bounded values (page >= 0, 1 <= size <= 100). */
public final class Paging {

    public static final int MAX_SIZE = 100;

    private Paging() {
    }

    public static Pageable of(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(0, page), Math.min(MAX_SIZE, Math.max(1, size)), sort);
    }

    /** {@code desc} (any case) means descending; every other value means ascending. */
    public static Sort.Direction direction(String direction) {
        return "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
    }
}
