package com.example.project3.repository;

import com.example.project3.entity.Student;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {

    // Sadece aktif (silinmemiş) kayıtları dinamik sıralama ile getirir
    List<Student> findByDeleted(Integer deleted, Sort sort);

    // Kayıt eklenirken silinmiş/aktif fark etmeksizin aynı öğrenci numarasının var olup olmadığını kontrol eder
    boolean existsByStudentNumber(String studentNumber);
}