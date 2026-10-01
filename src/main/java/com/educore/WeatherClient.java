package com.educore;

import com.educore.dto.ExternalWeatherResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "weatherClient", url = "https://api.open-meteo.com")
public interface WeatherClient {

    @GetMapping("/v1/forecast")
    ExternalWeatherResponse getWeather(
            @RequestParam("latitude") double latitude,
            @RequestParam("longitude") double longitude,
            @RequestParam("current_weather") boolean currentWeather
    );
}