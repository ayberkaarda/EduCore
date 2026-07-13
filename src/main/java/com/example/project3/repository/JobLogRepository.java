package com.example.project3.repository;

import org.springframework.data.domain.Sort;

public interface JobLogRepository {
    Object findAll(Sort createdAt);
}
