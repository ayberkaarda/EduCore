package com.example.project3.repository;

import com.example.project3.entity.JobLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface JobLogRepository extends JpaRepository<JobLog, Long> {
    // JpaRepository zaten findAll(Sort sort) ve save() metotlarını otomatik olarak barındırır.
}