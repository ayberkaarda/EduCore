package com.educore.auth;

import com.educore.lifecycle.Pseudonyms;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-21 / AC-12: the audit pseudonym of a purged account must not be reproducible through the login path. A failed
 * login with the username {@code account:<id>} stores {@code usernameHash = HMAC(pepper, "account:<id>")} in
 * {@code security_event.details}; with a shared key that value started with the account's pseudonym.
 */
class PseudonymDomainSeparationTest {

    /** TEST DATA ONLY. */
    private static final String PEPPER = "pseudonym-test-only-pepper-0123456789abcdef";

    private final UsernameHasher hasher = new UsernameHasher(PEPPER);
    private final Pseudonyms pseudonyms = new Pseudonyms(hasher);

    @Test
    void aLoginUsernameHashNeverReproducesAPurgePseudonym() {
        for (long id = 1; id <= 2_000; id++) {
            String pseudonym = pseudonyms.ofAccount(id);
            String loginOracle = "purged:" + hasher.hash("account:" + id).substring(0, 16);

            assertThat(pseudonym).as("account %d", id).isNotEqualTo(loginOracle);
            assertThat(hasher.hash("account:" + id)).doesNotStartWith(pseudonym.substring("purged:".length()));
        }
    }

    @Test
    void pseudonymsStayStableAndDistinctPerAccount() {
        assertThat(pseudonyms.ofAccount(42)).matches("purged:[0-9a-f]{16}")
                .isEqualTo(new Pseudonyms(new UsernameHasher(PEPPER)).ofAccount(42))
                .isNotEqualTo(pseudonyms.ofAccount(43));
        assertThat(new Pseudonyms(new UsernameHasher(PEPPER + "-other")).ofAccount(42))
                .as("a different pepper gives a different pseudonym").isNotEqualTo(pseudonyms.ofAccount(42));
    }

    @Test
    void derivedKeysAreIndependentOfTheUsernameKeyAndOfEachOther() {
        UsernameHasher.KeyedDigest audit = hasher.derive("educore/audit-pseudonym/v1");
        UsernameHasher.KeyedDigest other = hasher.derive("educore/other-purpose/v1");

        assertThat(audit.hex("x")).hasSize(64).isNotEqualTo(hasher.hash("x")).isNotEqualTo(other.hex("x"));
        // The derived key is not the username hash of its label either (it is HMAC(label, pepper), not
        // HMAC(pepper, label)), so logging in with the label as username reveals nothing.
        assertThat(audit.toString()).doesNotContain(PEPPER).contains("redacted");
    }
}
