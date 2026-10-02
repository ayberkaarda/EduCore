package com.educore.common.web;

/**
 * One entry of the {@code errors} member of a 400 problem: the request field (JSON property path, query
 * parameter or path variable name, {@code body} for the request body as a whole) and a stable machine code
 * such as {@code required}, {@code size}, {@code pattern}, {@code range}, {@code type}, {@code enum} or
 * {@code malformed}. The rejected value is never included.
 */
public record FieldViolation(String field, String code) implements Comparable<FieldViolation> {

    @Override
    public int compareTo(FieldViolation other) {
        int byField = field.compareTo(other.field);
        return byField != 0 ? byField : code.compareTo(other.code);
    }
}
