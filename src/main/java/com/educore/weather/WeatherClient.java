package com.educore.weather;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Open-Meteo forecast API. Connect and read timeouts (3 s) and disabled redirects are configured under
 * {@code spring.cloud.openfeign.client.config.weatherClient} in {@code application.yml}.
 */
@FeignClient(name = "weatherClient", url = "https://api.open-meteo.com")
public interface WeatherClient {

    @GetMapping("/v1/forecast")
    OpenMeteoResponse getWeather(
            @RequestParam("latitude") double latitude,
            @RequestParam("longitude") double longitude,
            @RequestParam("current_weather") boolean currentWeather);
}
