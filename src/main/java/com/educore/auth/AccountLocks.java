package com.educore.auth;

import com.educore.entity.Account;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Account-level locking and compare-and-set credential writes for the authentication flows.
 * <p>
 * Login (credential check plus session issuance) and password change (check, new hash, revocation of every
 * session, new session) each hold a {@code FOR NO KEY UPDATE} row lock on the account for their whole
 * transaction. They are therefore serialised per account: a login that verified the old password has
 * either issued its family before the password change revokes everything, or it waits and then reads the
 * new hash. {@code NO KEY UPDATE} still lets refresh-token inserts take their foreign-key share lock.
 * <p>
 * Returned accounts are detached snapshots: changing them never writes to the database. Credential writes
 * go through {@link #compareAndSetPassword}, which only succeeds while the stored hash is still the one
 * that was verified, so a stale copy can never overwrite a newer password.
 */
@Component
public class AccountLocks {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Account> lockByUsername(String username) {
        return single(entityManager.createNativeQuery(
                        "SELECT * FROM account WHERE username = :username FOR NO KEY UPDATE", Account.class)
                .setParameter("username", username)
                .getResultList());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Account> lockById(long id) {
        return single(entityManager.createNativeQuery(
                        "SELECT * FROM account WHERE id = :id FOR NO KEY UPDATE", Account.class)
                .setParameter("id", id)
                .getResultList());
    }

    /**
     * {@code UPDATE account SET password = :newHash WHERE id = :id AND password = :expectedHash}; also clears
     * {@code must_change_password} when {@code clearMustChange} is set. Returns whether the row changed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean compareAndSetPassword(long id, String expectedHash, String newHash, boolean clearMustChange) {
        String sql = clearMustChange
                ? "UPDATE account SET password = :newHash, must_change_password = false "
                        + "WHERE id = :id AND password = :expectedHash"
                : "UPDATE account SET password = :newHash WHERE id = :id AND password = :expectedHash";
        return entityManager.createNativeQuery(sql)
                .setParameter("newHash", newHash)
                .setParameter("id", id)
                .setParameter("expectedHash", expectedHash)
                .executeUpdate() == 1;
    }

    private Optional<Account> single(List<?> rows) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Account account = (Account) rows.get(0);
        // Always the row just read under the lock: refresh in case an earlier query of this transaction
        // already put an older copy into the persistence context, then detach so it is never flushed back.
        entityManager.refresh(account);
        entityManager.detach(account);
        return Optional.of(account);
    }
}
