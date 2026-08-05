package com.example.project3.repository;

import com.example.project3.entity.Account;
import com.example.project3.entity.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    java.util.Optional<Account> findByUsername(String username);

    // Hem aktif (isDeleted = 0) hem silinen (isDeleted = 1) öğrencileri getiren güncel metot
    @Query("SELECT a FROM Account a WHERE a.role = :role AND a.deleted = :isDeleted AND " +
            "(LOWER(a.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "LOWER(a.lastName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "LOWER(COALESCE(a.studentNumber, '')) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Account> searchAccountsByRoleAndDeleted(@Param("role") Role role, @Param("search") String search, @Param("isDeleted") int isDeleted, Pageable pageable);

    java.util.Optional<Account> findByStudentNumber(String studentNumber);
    java.util.Optional<Account> findByIpAddress(String ipAddress);

    @Query("SELECT a FROM Account a WHERE " +
            "LOWER(a.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "LOWER(a.lastName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "LOWER(COALESCE(a.studentNumber, '')) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<Account> searchAllAccounts(@Param("search") String search, Pageable pageable);

    Page<Account> findByDeletedAndFirstNameContainingIgnoreCaseOrDeletedAndLastNameContainingIgnoreCase(
            Integer deleted1, String firstName, Integer deleted2, String lastName, Pageable pageable
    );

    List<Account> findByDeleted(Integer deleted, Sort sort);

    boolean existsByStudentNumber(String studentNumber);
}