package com.educore.repository;

import com.educore.entity.Account;
import com.educore.entity.AccountStatus;
import com.educore.entity.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    java.util.Optional<Account> findByUsername(String username);

    /**
     * {@code pattern} is a {@code LIKE} pattern built by {@link com.educore.common.query.LikePatterns#contains}
     * (wildcards in the user input escaped with {@code !}); it is always a bound parameter. {@code statuses} is
     * the status set of the listing ({@code ACTIVE}, or every non-active status for {@code deleted=true}).
     */
    @Query("SELECT a FROM Account a WHERE a.role = :role AND a.status IN :statuses AND ("
            + "LOWER(a.firstName) LIKE LOWER(:pattern) ESCAPE '!' OR "
            + "LOWER(a.lastName) LIKE LOWER(:pattern) ESCAPE '!' OR "
            + "LOWER(COALESCE(a.studentNumber, '')) LIKE LOWER(:pattern) ESCAPE '!')")
    Page<Account> searchAccountsByRoleAndStatus(
            @Param("role") Role role,
            @Param("pattern") String pattern,
            @Param("statuses") Collection<AccountStatus> statuses,
            Pageable pageable
    );

    java.util.Optional<Account> findByStudentNumber(String studentNumber);
    java.util.Optional<Account> findByIpAddress(String ipAddress);

    boolean existsByStudentNumber(String studentNumber);

    boolean existsByRole(Role role);

    /** Like {@link #searchAccountsByRoleAndStatus} for every role; also matches the username. */
    @Query("SELECT a FROM Account a WHERE a.status IN :statuses AND ("
            + "LOWER(a.firstName) LIKE LOWER(:pattern) ESCAPE '!' OR "
            + "LOWER(a.lastName) LIKE LOWER(:pattern) ESCAPE '!' OR "
            + "LOWER(a.username) LIKE LOWER(:pattern) ESCAPE '!' OR "
            + "LOWER(COALESCE(a.studentNumber, '')) LIKE LOWER(:pattern) ESCAPE '!')")
    Page<Account> searchByStatus(@Param("pattern") String pattern,
                                 @Param("statuses") Collection<AccountStatus> statuses, Pageable pageable);

    /**
     * Ids of every active ADMIN, locking those rows until the transaction ends. Two transactions that both
     * want to remove an ADMIN serialize here, and the second one re-reads the set after the first commits,
     * so the "at least one active ADMIN" rule cannot be broken by concurrent requests.
     */
    @Query(value = "SELECT id FROM account WHERE role = 'ADMIN' AND status = 'ACTIVE' ORDER BY id FOR UPDATE",
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
     * active account. It never writes role or status, so it cannot undo a concurrent demotion, soft delete
     * or deletion request, and it updates nothing once the account is no longer active.
     *
     * @return the number of rows updated (0 or 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Account a SET a.firstName = :firstName, a.lastName = :lastName, a.version = a.version + 1 "
            + "WHERE a.id = :id AND a.status = com.educore.entity.AccountStatus.ACTIVE")
    int updateOwnName(@Param("id") Long id, @Param("firstName") String firstName,
                      @Param("lastName") String lastName);

    /**
     * {@code ACTIVE -> PENDING_DELETION}: writes only the lifecycle columns (and bumps the version) of an active
     * account, so a concurrent edit of other fields is never overwritten. The session epoch is incremented too,
     * so every access token issued before the request stops authenticating at once.
     *
     * @return the number of rows updated (0 or 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Account a SET a.status = :pending, a.deletedAt = :now, a.deleteAfter = :deleteAfter, "
            + "a.sessionEpoch = a.sessionEpoch + 1, a.version = a.version + 1 "
            + "WHERE a.id = :id AND a.status = :active")
    int markPendingDeletion(@Param("id") Long id, @Param("now") Instant now, @Param("deleteAfter") Instant deleteAfter,
                            @Param("pending") AccountStatus pending, @Param("active") AccountStatus active);

    /**
     * Id of the next PENDING_DELETION account whose grace period ended at or before {@code now}, locked
     * ({@code FOR UPDATE SKIP LOCKED}): concurrent purge runs (other threads or instances) skip a row another
     * run holds, and a row locked by a login or restore in progress is left for the next run.
     */
    @Query(value = "SELECT id FROM account WHERE status = 'PENDING_DELETION' AND delete_after <= :now "
            + "ORDER BY delete_after, id LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Long> lockNextDueForPurge(@Param("now") Instant now);
}
