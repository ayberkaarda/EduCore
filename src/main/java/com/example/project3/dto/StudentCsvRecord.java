package com.example.project3.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StudentCsvRecord {
    private String firstName;
    private String lastName;
    private String studentNumber;
}