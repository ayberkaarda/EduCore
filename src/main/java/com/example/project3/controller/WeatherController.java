package com.example.project3.controller;

import com.example.project3.dto.CityWeatherDTO;
import com.example.project3.service.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/weather")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // Gerekirse Frontend portunuza göre düzenleyin
public class WeatherController {

    private final WeatherService weatherService;

    @GetMapping
    public ResponseEntity<List<CityWeatherDTO>> getWeather() {
        return ResponseEntity.ok(weatherService.getCitiesWeather());
    }
}