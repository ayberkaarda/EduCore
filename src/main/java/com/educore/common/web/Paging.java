package com.educore.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link Pageable}s from request parameters. Controllers validate {@code page >= 0} and
 * {@code 1 <= size <= MAX_SIZE} with Bean Validation (400 {@code request/invalid}); this class re-checks the
 * bounds so a service can never be called with an unbounded page.
 */
public final class Paging {

    public static final int MAX_SIZE = 100;

    private Paging() {
    }

    /**
     * @throws ApiProblemException 400 {@code request/invalid} when {@code page < 0}, {@code size} is
     *                             outside 1..{@value #MAX_SIZE} or the row offset {@code page * size} exceeds
     *                             {@link Integer#MAX_VALUE} (values are rejected, never silently capped)
     */
    public static Pageable of(int page, int size, Sort sort) {
        List<FieldViolation> errors = new ArrayList<>();
        if (page < 0) {
            errors.add(new FieldViolation("page", "range"));
        }
        if (size < 1 || size > MAX_SIZE) {
            errors.add(new FieldViolation("size", "range"));
        } else if (page >= 0 && (long) page * size > Integer.MAX_VALUE) {
            // The row offset is an int in JPA (setFirstResult); a larger offset would overflow.
            errors.add(new FieldViolation("page", "range"));
        }
        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, Problems.INVALID_REQUEST, "The request is invalid.",
                    null, Map.of(Problems.ERRORS, List.copyOf(errors)));
        }
        return PageRequest.of(page, size, sort);
    }
}
