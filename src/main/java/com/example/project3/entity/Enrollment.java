package com.example.project3.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity

@Table(name = "enrollments", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"account_id", "course_id"})
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Enrollment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // FetchType.LAZY performansı artırır, gereksiz sorguları önler (N+1 problemi)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Builder.Default
    private LocalDateTime enrollmentDate = LocalDateTime.now();
}