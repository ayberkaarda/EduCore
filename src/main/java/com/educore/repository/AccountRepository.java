package com.educore.repository;

import com.educore.entity.Account;
import com.educore.entity.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    java.util.Optional<Account> findByUsername(String username);

    // KUSURSUZ VE HATASIZ İMZA: Dönüş tipi Page<Account> olarak netleştirildi
    @Query("SELECT a FROM Account a WHERE a.role = :role AND a.deleted = :isDeleted AND " +
            "(LOWER(a.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "LOWER(a.lastName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "LOWER(COALESCE(a.studentNumber, '')) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Account> searchAccountsByRoleAndDeleted(
            @Param("role") Role role,
            @Param("search") String search,
            @Param("isDeleted") int isDeleted,
            Pageable pageable
    );

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

    boolean existsByRole(Role role);

    @Query("SELECT a FROM Account a WHERE a.deleted = :isDeleted AND (" +
            "LOWER(a.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "LOWER(a.lastName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "LOWER(a.username) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "LOWER(COALESCE(a.studentNumber, '')) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Account> searchByDeleted(@Param("search") String search, @Param("isDeleted") int isDeleted,
                                  Pageable pageable);

    /**
     * Ids of every active ADMIN, locking those rows until the transaction ends. Two transactions that both
     * want to remove an ADMIN serialize here, and the second one re-reads the set after the first commits,
     * so the "at least one active ADMIN" rule cannot be broken by concurrent requests.
     */
    @Query(value = "SELECT id FROM account WHERE role = 'ADMIN' AND deleted = 0 ORDER BY id FOR UPDATE",
            nativeQuery = true)
    List<Long> lockActiveAdminIds();

    /**
     * Loads the account and locks its row ({@code SELECT ... FOR UPDATE}) until the transaction ends, so an
     * admin mutation always starts from the latest committed state and concurrent writers of the same row wait.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    java.util.Optional<Account> findByIdForUpdate(@Param("id") Long id);

    /**
     * Field-specific self-service update: writes only the two name columns (and bumps the version) of an
     * active account. It never writes role or deleted, so it cannot undo a concurrent demotion or soft delete,
     * and it updates nothing once the account is soft-deleted.
     *
     * @return the number of rows updated (0 or 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Account a SET a.firstName = :firstName, a.lastName = :lastName, a.version = a.version + 1 "
            + "WHERE a.id = :id AND a.deleted = 0")
    int updateOwnName(@Param("id") Long id, @Param("firstName") String firstName,
                      @Param("lastName") String lastName);
}
