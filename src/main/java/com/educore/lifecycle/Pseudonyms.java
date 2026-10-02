package com.educore.lifecycle;

import com.educore.auth.UsernameHasher;
import org.springframework.stereotype.Component;

/**
 * Pseudonym of a purged account in the audit trail: {@code purged:<first 16 hex>} of HMAC-SHA-256 over
 * {@code account:<id>} under a key derived from the server-side pepper ({@code EDUCORE_LOGIN_PEPPER}) for this
 * purpose only ({@link UsernameHasher#derive}, label {@value #KEY_LABEL}). The same account always maps to the
 * same pseudonym (while the pepper is unchanged), so its events stay correlatable; without the pepper the id
 * cannot be recovered by trying every id.
 * <p>
 * Domain separation (R-21): the login {@code usernameHash} is keyed with the pepper itself, so before this key
 * derivation a login attempt with the username {@code account:<id>} wrote exactly that account's pseudonym into
 * {@code security_event.details}. Pseudonyms written before the change are not recomputed (the purged ids are
 * gone, so they cannot be); they stay as written, and only purges from now on use the derived key
 * (docs/ops/DATA_RETENTION.md).
 */
@Component
public class Pseudonyms {

    static final String PREFIX = "purged:";
    static final String KEY_LABEL = "educore/audit-pseudonym/v1";
    private static final int HEX_LENGTH = 16;

    private final UsernameHasher.KeyedDigest digest;

    public Pseudonyms(UsernameHasher keyedHash) {
        this.digest = keyedHash.derive(KEY_LABEL);
    }

    public String ofAccount(long accountId) {
        return PREFIX + digest.hex("account:" + accountId).substring(0, HEX_LENGTH);
    }
}
