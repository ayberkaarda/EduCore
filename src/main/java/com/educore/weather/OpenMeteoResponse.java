package com.educore.weather;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The part of the Open-Meteo forecast response the widget uses. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenMeteoResponse(@JsonProperty("current_weather") CurrentWeather currentWeather) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CurrentWeather(Double temperature, Double windspeed, Integer weathercode) {
    }
}
