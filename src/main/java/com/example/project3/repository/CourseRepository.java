package com.example.project3.repository;
import com.example.project3.entity.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CourseRepository extends JpaRepository<Course, Long> {
    java.util.Optional<Course> findByName(String name); // YENİ: İsim kontrolü için
}