package com.educore.common.web;

import org.springframework.data.domain.Sort;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The sort keys one endpoint accepts, each mapped to the entity property it orders by. Client input is only
 * ever used as a lookup key: the property names handed to {@link Sort} come from this whitelist, never from
 * the request. An unknown key or a direction other than {@code asc}/{@code desc} (any case) is a 400
 * {@code sort/invalid}. The configured tie-breaker (usually {@code id}) is appended so pages are stable.
 * <pre>{@code
 * SortWhitelist.of("firstName", Sort.by("id"))
 *         .allow("firstName", "firstName")
 *         .allow("studentNumber", "studentNumber");
 * }</pre>
 */
public final class SortWhitelist {

    private static final String TITLE = "The sort parameter is not supported by this endpoint.";

    private final String defaultKey;
    private final Sort tieBreaker;
    private final Map<String, String> properties;

    private SortWhitelist(String defaultKey, Sort tieBreaker, Map<String, String> properties) {
        this.defaultKey = defaultKey;
        this.tieBreaker = tieBreaker;
        this.properties = properties;
    }

    /** A whitelist whose {@code defaultKey} is used when the request names no sort key. */
    public static SortWhitelist of(String defaultKey, Sort tieBreaker) {
        return new SortWhitelist(defaultKey, tieBreaker, Map.of());
    }

    /** A copy that also accepts {@code key}, ordering by the entity property {@code property}. */
    public SortWhitelist allow(String key, String property) {
        Map<String, String> next = new LinkedHashMap<>(properties);
        next.put(key, property);
        return new SortWhitelist(defaultKey, tieBreaker, Map.copyOf(next));
    }

    /** The accepted keys. */
    public Set<String> keys() {
        return properties.keySet();
    }

    /**
     * The sort for {@code key} (blank or {@code null}: the default key) in {@code direction} (blank or
     * {@code null}: ascending), followed by the tie-breaker.
     *
     * @throws ApiProblemException 400 {@code sort/invalid} for an unknown key or direction
     */
    public Sort resolve(String key, String direction) {
        String requested = key == null || key.isBlank() ? defaultKey : key;
        String property = properties.get(requested);
        if (property == null) {
            throw ApiProblemException.badRequest(Problems.SORT_INVALID, TITLE);
        }
        Sort sort = Sort.by(direction(direction), property);
        return tieBreaker.isSorted() && !property.equals(firstProperty(tieBreaker)) ? sort.and(tieBreaker) : sort;
    }

    /** {@code asc}, {@code desc} (any case) or blank (ascending). */
    public static Sort.Direction direction(String direction) {
        if (direction == null || direction.isBlank() || "asc".equalsIgnoreCase(direction)) {
            return Sort.Direction.ASC;
        }
        if ("desc".equalsIgnoreCase(direction)) {
            return Sort.Direction.DESC;
        }
        throw ApiProblemException.badRequest(Problems.SORT_INVALID, TITLE);
    }

    private static String firstProperty(Sort sort) {
        return sort.iterator().next().getProperty();
    }
}
