package com.example.project3.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CityWeatherDTO {
    private String city;
    private Double temperature;
    private Double windSpeed;
    private Integer weatherCode;
    private String description;
}