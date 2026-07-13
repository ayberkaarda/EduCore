package com.example.project3.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fileName;
    private int successfulRecords;
    private int failedRecords;
    private String status; // SUCCESS veya FAILED
    private LocalDateTime createdAt;
    @Column(columnDefinition = "TEXT")
    private String detailedLogs;
    private String entityType; // YENİ: STUDENTS veya COURSES
}