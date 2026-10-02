package com.educore.auth;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;

/**
 * Serialises credential checks per username hash with a PostgreSQL transaction-scoped advisory lock.
 * <p>
 * Lock check, password verification and the attempt record of one username hash run under this lock and
 * commit before it is released, so parallel requests (from any number of IPs) see each other's failures and
 * at most {@code max-failures} attempts are ever verified before the lock applies. Login and password change
 * use the same key. Unrelated usernames only contend when their hashes share the 32-bit key.
 * <p>
 * A request waits at most {@link #WAIT} for the lock (each holder needs one bcrypt check), so a burst
 * against one username cannot pin pooled connections indefinitely; it then gets {@link Busy}.
 */
@Component
public class AttemptLocks {

    /** First argument of the two-key advisory lock, so EduCore's keys never meet other advisory lock users. */
    static final int NAMESPACE = 0x45444331;
    static final String WAIT = "5s";
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    @PersistenceContext
    private EntityManager entityManager;

    /** The lock could not be obtained within {@link #WAIT}; the transaction must roll back. */
    public static final class Busy extends RuntimeException {
        Busy(Throwable cause) {
            super("Username attempt lock busy", cause, false, false);
        }
    }

    /** Blocks until the lock for {@code usernameHash} is held; it is released when the transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String usernameHash) {
        String previous = (String) entityManager.createNativeQuery("SELECT current_setting('lock_timeout')")
                .getSingleResult();
        setLockTimeout(WAIT);
        try {
            entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(:namespace, :key)")
                    .setParameter("namespace", NAMESPACE)
                    .setParameter("key", key(usernameHash))
                    .getSingleResult();
        } catch (PersistenceException e) {
            if (hasSqlState(e, LOCK_NOT_AVAILABLE)) {
                throw new Busy(e);
            }
            throw e;
        }
        setLockTimeout(previous);
    }

    private void setLockTimeout(String value) {
        entityManager.createNativeQuery("SELECT set_config('lock_timeout', :value, true)")
                .setParameter("value", value)
                .getSingleResult();
    }

    static int key(String usernameHash) {
        return (int) Long.parseLong(usernameHash.substring(0, 8), 16);
    }

    private static boolean hasSqlState(Throwable e, String state) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && state.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
