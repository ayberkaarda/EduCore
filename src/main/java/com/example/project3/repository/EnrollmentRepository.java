package com.example.project3.repository;

import com.example.project3.entity.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    java.util.List<Enrollment> findByAccountId(Long accountId);
    boolean existsByAccountIdAndCourseId(Long accountId, Long courseId);

    // BUNUN EKLİ OLMASI ŞART! (Dersi Bırakma Metodu)
    void deleteByAccountIdAndCourseId(Long accountId, Long courseId);
}