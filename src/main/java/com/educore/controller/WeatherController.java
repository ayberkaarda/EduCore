package com.educore.controller;

import com.educore.dto.CityWeatherDTO;
import com.educore.service.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Weather for the dashboard widget: {@code GET /api/v1/weather} (any authenticated caller). */
@RestController
@RequestMapping("/api/v1/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;

    @GetMapping
    public ResponseEntity<List<CityWeatherDTO>> getWeather() {
        return ResponseEntity.ok(weatherService.getCitiesWeather());
    }
}