package com.educore.weather;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Weather for the dashboard widget (D-05), proxied through the backend.
 * <p>
 * Each city is cached for {@link #FRESH_FOR} (Caffeine; concurrent requests for the same city wait for one
 * provider call), so the provider sees at most one call per city per period however many clients ask. When a
 * call fails (timeout, error status, unreadable body) the last good value is returned as {@code STALE}, or an
 * {@code UNAVAILABLE} entry when there is none; the failure result is cached for {@link #RETRY_AFTER_FAILURE}
 * so a broken provider is not called on every request. The response always lists every city.
 */
@Service
public class WeatherService {

    static final Duration FRESH_FOR = Duration.ofMinutes(5);
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    /** Cities shown by the widget, in display order. */
    static final List<City> CITIES = List.of(
            new City("İstanbul", 41.0082, 28.9784),
            new City("Ankara", 39.9334, 32.8597),
            new City("İzmir", 38.4192, 27.1287));

    private final WeatherClient weatherClient;
    private final Clock clock;
    private final Cache<String, CityWeather> cache;
    private final Map<String, CityWeather> lastGood = new ConcurrentHashMap<>();

    @Autowired
    public WeatherService(WeatherClient weatherClient, Clock clock) {
        this(weatherClient, clock, Ticker.systemTicker());
    }

    WeatherService(WeatherClient weatherClient, Clock clock, Ticker ticker) {
        this.weatherClient = weatherClient;
        this.clock = clock;
        this.cache = Caffeine.newBuilder()
                .maximumSize(CITIES.size() * 2L)
                .ticker(ticker)
                .expireAfter(Expiry.creating((String city, CityWeather value) ->
                        value.status() == CityWeather.Status.OK ? FRESH_FOR : RETRY_AFTER_FAILURE))
                .build();
    }

    /** One entry per city, never an empty list. */
    public List<CityWeather> getCitiesWeather() {
        return CITIES.stream().map(city -> cache.get(city.name(), name -> fetch(city))).toList();
    }

    private CityWeather fetch(City city) {
        try {
            OpenMeteoResponse response = weatherClient.getWeather(city.latitude(), city.longitude(), true);
            OpenMeteoResponse.CurrentWeather current = response == null ? null : response.currentWeather();
            if (current != null && current.temperature() != null && current.windspeed() != null
                    && current.weathercode() != null) {
                CityWeather fresh = new CityWeather(city.name(), current.temperature(), current.windspeed(),
                        current.weathercode(), describe(current.weathercode()), CityWeather.Status.OK,
                        clock.instant());
                lastGood.put(city.name(), fresh);
                return fresh;
            }
            // An incomplete payload never replaces the last good value.
            log.warn("Weather provider returned incomplete current weather for city={}", city.name());
        } catch (RuntimeException e) {
            // The exception type is enough to diagnose; messages can quote the provider URL and response.
            log.warn("Weather provider call failed for city={} error={}", city.name(), e.getClass().getSimpleName());
        }
        CityWeather previous = lastGood.get(city.name());
        return previous != null ? previous.asStale() : CityWeather.unavailable(city.name());
    }

    static String describe(Integer code) {
        if (code == null) {
            return CityWeather.UNKNOWN_DESCRIPTION;
        }
        return switch (code) {
            case 0 -> "Açık";
            case 1, 2, 3 -> "Parçalı Bulutlu";
            case 45, 48 -> "Sisli";
            case 51, 53, 55, 61, 63, 65 -> "Yağmurlu";
            case 71, 73, 75 -> "Karlı";
            case 95, 96, 99 -> "Fırtınalı";
            default -> "Bulutlu";
        };
    }

    record City(String name, double latitude, double longitude) {
    }
}
