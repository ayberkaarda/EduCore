package com.example.project3.repository;

import com.example.project3.entity.Account;
import com.example.project3.entity.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    java.util.Optional<Account> findByUsername(String username);
    @Query("SELECT a FROM Account a WHERE a.role = :role AND " +
            "(LOWER(a.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "LOWER(a.lastName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "LOWER(COALESCE(a.studentNumber, '')) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Account> searchAccountsByRole(@Param("role") Role role, @Param("search") String search, Pageable pageable);
    java.util.Optional<Account> findByStudentNumber(String studentNumber);
    @Query("SELECT a FROM Account a WHERE " +
            "LOWER(a.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "LOWER(a.lastName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "LOWER(COALESCE(a.studentNumber, '')) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<Account> searchAllAccounts(@Param("search") String search, Pageable pageable);
}