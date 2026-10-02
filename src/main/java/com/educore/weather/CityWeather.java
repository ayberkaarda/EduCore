package com.educore.weather;

import java.time.Instant;

/**
 * Weather of one city as returned by {@code GET /api/v1/weather}. {@code status}:
 * <ul>
 *   <li>{@code OK}: fetched from the provider within the last cache period;</li>
 *   <li>{@code STALE}: the provider failed, these are the last values fetched successfully
 *       ({@code observedAt} says when);</li>
 *   <li>{@code UNAVAILABLE}: the provider failed and no earlier value exists; the measurements are
 *       {@code null}.</li>
 * </ul>
 */
public record CityWeather(String city, Double temperature, Double windSpeed, Integer weatherCode,
                          String description, Status status, Instant observedAt) {

    public enum Status { OK, STALE, UNAVAILABLE }

    static final String UNKNOWN_DESCRIPTION = "Bilinmiyor";

    static CityWeather unavailable(String city) {
        return new CityWeather(city, null, null, null, UNKNOWN_DESCRIPTION, Status.UNAVAILABLE, null);
    }

    CityWeather asStale() {
        return new CityWeather(city, temperature, windSpeed, weatherCode, description, Status.STALE, observedAt);
    }
}
