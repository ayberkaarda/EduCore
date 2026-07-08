package com.example.project3.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Entity
@Table(name = "courses")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Course {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Ders adı boş bırakılamaz")
    private String name;

    @NotBlank(message = "Dönem bilgisi (örn: 2026/1) boş bırakılamaz")
    private String term;
}