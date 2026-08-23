package com.example.project3;

import com.example.project3.dto.ExternalWeatherResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "weatherClient", url = "https://api.open-meteo.com/v1")
public interface WeatherClient {
    @GetMapping("/forecast")
    ExternalWeatherResponse getWeather(
            @RequestParam("latitude") double latitude,
            @RequestParam("longitude") double longitude,
            @RequestParam("current_weather") boolean currentWeather
    );
}