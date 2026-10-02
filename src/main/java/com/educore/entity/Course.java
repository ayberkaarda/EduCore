package com.educore.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

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

    /** Public URL key ({@code V40__course_public_fields.sql}); required once the course is published. */
    @Column(length = 80, unique = true)
    private String slug;

    /** Plain-text public summary. */
    @Column(length = 1000)
    private String description;

    /** Only published courses are exposed by the anonymous public API and the sitemap. */
    @Column(nullable = false)
    private boolean published;

    /**
     * Optimistic lock ({@code V41__course_version.sql}): a write based on a stale read fails with 409
     * {@code request/concurrent-modification} instead of restoring an old published flag or slug.
     */
    @Version
    private Long version;

    /** Set on every insert and update; the sitemap {@code lastmod} of the course page. */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}