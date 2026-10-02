package com.educore.weather;

import com.educore.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * Weather for the dashboard widget: {@code GET /api/v1/weather} (any authenticated caller), at most
 * {@code educore.weather.requests-per-minute} (30) requests per account and minute; beyond that 429
 * {@code weather/too-many-requests} with {@code Retry-After}. Independent of any global rate limit.
 */
@RestController
@RequestMapping("/api/v1/weather")
@Validated
public class WeatherController {

    static final String TOO_MANY = "weather/too-many-requests";

    private final WeatherService weatherService;
    private final PerUserRateLimiter limiter;

    public WeatherController(WeatherService weatherService,
                             @Value("${educore.weather.requests-per-minute:30}") int requestsPerMinute) {
        this.weatherService = weatherService;
        this.limiter = new PerUserRateLimiter(requestsPerMinute, Duration.ofMinutes(1));
    }

    @GetMapping
    public List<CityWeather> getWeather(@AuthenticationPrincipal AuthenticatedUser user) {
        Object key = user == null ? "anonymous" : user.id();
        limiter.tryAcquire(key).ifPresent(wait -> {
            throw new RateLimitedException(TOO_MANY, "Too many weather requests; try again later.", wait);
        });
        return weatherService.getCitiesWeather();
    }
}
