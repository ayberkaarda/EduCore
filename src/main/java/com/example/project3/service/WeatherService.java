package com.example.project3.service;

import com.example.project3.client.WeatherClient;
import com.example.project3.dto.CityWeatherDTO;
import com.example.project3.dto.ExternalWeatherResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WeatherService {

    private final WeatherClient weatherClient;

    // Gösterilecek şehirler ve koordinatları
    private static final Map<String, double[]> CITIES = Map.of(
            "İstanbul", new double[]{41.0082, 28.9784},
            "Ankara", new double[]{39.9334, 32.8597},
            "İzmir", new double[]{38.4192, 27.1287}
    );

    public List<CityWeatherDTO> getCitiesWeather() {
        List<CityWeatherDTO> weatherList = new ArrayList<>();

        for (Map.Entry<String, double[]> entry : CITIES.entrySet()) {
            String city = entry.getKey();
            double lat = entry.getValue()[0];
            double lon = entry.getValue()[1];

            try {
                ExternalWeatherResponse response = weatherClient.getWeather(lat, lon, true);
                if (response != null && response.getCurrentWeather() != null) {
                    var current = response.getCurrentWeather();
                    weatherList.add(CityWeatherDTO.builder()
                            .city(city)
                            .temperature(current.getTemperature())
                            .windSpeed(current.getWindspeed())
                            .weatherCode(current.getWeathercode())
                            .description(mapWeatherCodeToText(current.getWeathercode()))
                            .build());
                }
            } catch (Exception e) {
                // Şehir çekilemezse fallback ekle
                weatherList.add(CityWeatherDTO.builder()
                        .city(city)
                        .temperature(null)
                        .description("Bilinmiyor")
                        .build());
            }
        }
        return weatherList;
    }

    private String mapWeatherCodeToText(Integer code) {
        if (code == null) return "Bilinmiyor";
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
}