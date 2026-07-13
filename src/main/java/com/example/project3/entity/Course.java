package com.example.project3.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Course {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Ders adı boş bırakılamaz")
    @Column(unique = true, nullable = false)
    private String name; // YENİ: Ders ismi benzersiz!

    private String term;
    private String instructor; // YENİ: Dersi veren hoca
}