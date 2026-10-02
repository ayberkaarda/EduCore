package com.educore.common.query;

/**
 * Builds {@code LIKE} patterns from user search input. The input is bound as a query parameter (never
 * concatenated into query text) and its wildcard characters are escaped, so a search for {@code 50%} or
 * {@code a_b} matches those characters literally instead of acting as a wildcard. Queries that use these
 * patterns must declare the escape character: {@code ... LIKE :pattern ESCAPE '!'}.
 */
public final class LikePatterns {

    /** The escape character to declare in the query's {@code ESCAPE} clause. */
    public static final char ESCAPE = '!';

    private LikePatterns() {
    }

    /** {@code value} with {@code !}, {@code %} and {@code _} prefixed by {@link #ESCAPE}; {@code null} as empty. */
    public static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ESCAPE || c == '%' || c == '_') {
                escaped.append(ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    /** A pattern matching values that contain {@code value} literally ({@code %<escaped>%}). */
    public static String contains(String value) {
        return "%" + escape(value == null ? "" : value.trim()) + "%";
    }
}
