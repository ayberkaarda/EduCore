package com.educore.weather;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** D-05: five-minute cache, stale value or explicit UNAVAILABLE on provider failure, never an empty list. */
class WeatherServiceTest {

    private final WeatherClient client = mock(WeatherClient.class);
    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T09:00:00Z"), ZoneOffset.UTC);
    private final WeatherService service = new WeatherService(client, clock, ticker);

    private static OpenMeteoResponse sunny(double temperature) {
        return new OpenMeteoResponse(new OpenMeteoResponse.CurrentWeather(temperature, 10.0, 0));
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    @Test
    void cachesEachCityForFiveMinutes() {
        when(client.getWeather(anyDouble(), anyDouble(), anyBoolean())).thenReturn(sunny(21.5));

        List<CityWeather> first = service.getCitiesWeather();
        advance(Duration.ofMinutes(4));
        service.getCitiesWeather();

        assertThat(first).hasSize(3).allSatisfy(city -> {
            assertThat(city.status()).isEqualTo(CityWeather.Status.OK);
            assertThat(city.temperature()).isEqualTo(21.5);
            assertThat(city.description()).isEqualTo("Açık");
            assertThat(city.observedAt()).isEqualTo(clock.instant());
        });
        assertThat(first).extracting(CityWeather::city).containsExactly("İstanbul", "Ankara", "İzmir");
        verify(client, times(3)).getWeather(anyDouble(), anyDouble(), anyBoolean());

        advance(Duration.ofMinutes(2));
        service.getCitiesWeather();
        verify(client, times(6)).getWeather(anyDouble(), anyDouble(), anyBoolean());
    }

    @Test
    void returnsTheLastGoodValueAsStaleWhenTheProviderFails() {
        when(client.getWeather(anyDouble(), anyDouble(), anyBoolean()))
                .thenReturn(sunny(18.0), sunny(18.0), sunny(18.0))
                .thenThrow(new IllegalStateException("provider timeout"));

        service.getCitiesWeather();
        advance(Duration.ofMinutes(6));
        List<CityWeather> afterFailure = service.getCitiesWeather();

        assertThat(afterFailure).hasSize(3).allSatisfy(city -> {
            assertThat(city.status()).isEqualTo(CityWeather.Status.STALE);
            assertThat(city.temperature()).isEqualTo(18.0);
        });
    }

    @Test
    void returnsExplicitUnavailableEntriesWhenNothingWasEverFetched() {
        when(client.getWeather(anyDouble(), anyDouble(), anyBoolean())).thenThrow(new IllegalStateException("down"));

        List<CityWeather> weather = service.getCitiesWeather();

        assertThat(weather).hasSize(3).allSatisfy(city -> {
            assertThat(city.status()).isEqualTo(CityWeather.Status.UNAVAILABLE);
            assertThat(city.temperature()).isNull();
            assertThat(city.description()).isEqualTo("Bilinmiyor");
        });
    }

    @Test
    void aFailureIsRetriedAfterOneMinuteNotOnEveryRequest() {
        when(client.getWeather(anyDouble(), anyDouble(), anyBoolean()))
                .thenThrow(new IllegalStateException("down"))
                .thenThrow(new IllegalStateException("down"))
                .thenThrow(new IllegalStateException("down"))
                .thenReturn(sunny(5.0));

        service.getCitiesWeather();
        service.getCitiesWeather();
        verify(client, times(3)).getWeather(anyDouble(), anyDouble(), anyBoolean());

        advance(Duration.ofSeconds(61));
        assertThat(service.getCitiesWeather()).allSatisfy(city ->
                assertThat(city.status()).isEqualTo(CityWeather.Status.OK));
    }
}
