package com.educore.repository;

import com.educore.entity.Course;
import com.educore.entity.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    @Query("SELECT e.course FROM Enrollment e WHERE e.account.id = :accountId ORDER BY e.course.name")
    List<Course> findCoursesByAccountId(@Param("accountId") Long accountId);

    boolean existsByAccountIdAndCourseId(Long accountId, Long courseId);

    @Modifying
    @Query("DELETE FROM Enrollment e WHERE e.account.id = :accountId AND e.course.id = :courseId")
    int deleteByAccountIdAndCourseId(@Param("accountId") Long accountId, @Param("courseId") Long courseId);
}
