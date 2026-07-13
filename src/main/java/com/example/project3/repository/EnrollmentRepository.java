package com.example.project3.repository;

import com.example.project3.entity.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    List<Enrollment> findByAccountId(Long accountId);
    boolean existsByAccountIdAndCourseId(Long accountId, Long courseId);
    void deleteByAccountIdAndCourseId(Long accountId, Long courseId);
}