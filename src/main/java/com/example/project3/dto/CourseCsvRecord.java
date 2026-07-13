package com.example.project3.dto;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CourseCsvRecord {
    private String name;
    private String term;
    private String instructor;
}