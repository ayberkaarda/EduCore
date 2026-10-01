package com.educore.auth;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    List<LoginAttempt> findByUsernameHashOrderByAtDescIdDesc(String usernameHash, Pageable pageable);
}
