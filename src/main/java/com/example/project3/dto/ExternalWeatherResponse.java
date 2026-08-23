package com.example.project3.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ExternalWeatherResponse {
    @JsonProperty("current_weather")
    private CurrentWeather currentWeather;

    @Data
    public static class CurrentWeather {
        private Double temperature;
        private Double windspeed;
        private Integer weathercode;
    }
}